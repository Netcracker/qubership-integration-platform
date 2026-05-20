import type { TextAreaRef } from "antd/es/input/TextArea";
import React, {
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { getDefaultAiProvider } from "../../ai/config.ts";
import type { ChatRequest } from "../../ai/modelProviders/types.ts";
import {
  ChatMessage,
  ChatResponse,
  StreamingChunk,
} from "../../ai/modelProviders/types.ts";
import { AiModelProvider } from "../../ai/modelProviders/AiModelProvider.ts";
import { ChatSession } from "../../ai/sessions/types.ts";
import { getChatSessionStore } from "../../ai/sessions/sessionStore.ts";
import { getConfig } from "../../appConfig.ts";
import { ChainContext as PageChainContext } from "../../pages/ChainPage.tsx";
import type { ChainModificationProposal } from "./ChainModificationConfirmation.tsx";
import {
  buildMetaMessage,
  getResponseTail,
  parseChatMeta,
  type ResponseResult,
  upsertAssistantMessage,
} from "./chatMessageUtils.ts";
import {
  extractDesignUrlFromMessages,
  lastUserMessageIsAgree,
  tryParseChainModificationProposal,
} from "./chainModificationContent.ts";
import { WORKING_DOTS } from "./aiAssistantConstants.ts";
import { useChainContext } from "./useChainContext.ts";

export function useAiAssistantChat(open: boolean) {
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [providerError, setProviderError] = useState<string | null>(null);
  const sessionStore = getChatSessionStore();
  const chainContext = useChainContext();
  const pageChainContext = useContext(PageChainContext);

  const [currentSession, setCurrentSession] = useState<ChatSession | null>(
    null,
  );
  const [inputValue, setInputValue] = useState("");
  const abortControllerRef = useRef<AbortController | null>(null);

  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const shouldAutoScrollRef = useRef(true);
  const sendInProgressRef = useRef(false);

  const [pendingProposal, setPendingProposal] =
    useState<ChainModificationProposal | null>(null);
  const [isConfirmationOpen, setIsConfirmationOpen] = useState(false);
  const [showLongRunningHint, setShowLongRunningHint] = useState(false);
  const [attachedFiles, setAttachedFiles] = useState<File[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const assistantName = getConfig().aiAssistantName ?? "Assistant";
  const [workingDots, setWorkingDots] = useState(0);
  const inputRef = React.useRef<TextAreaRef | null>(null);

  useEffect(() => {
    if (!isLoading && !isStreaming) {
      setShowLongRunningHint(false);
      return;
    }
    const t = setTimeout(() => setShowLongRunningHint(true), 4000);
    return () => clearTimeout(t);
  }, [isLoading, isStreaming]);

  useEffect(() => {
    if (!isLoading && !isStreaming) return;
    const interval = setInterval(() => {
      setWorkingDots((d) => (d + 1) % WORKING_DOTS.length);
    }, 400);
    return () => clearInterval(interval);
  }, [isLoading, isStreaming]);

  useEffect(() => {
    if (currentSessionId) {
      const session = sessionStore.getSession(currentSessionId);
      if (session) {
        setCurrentSession((prevSession) => {
          if (prevSession && prevSession.id === currentSessionId) {
            const prevMessagesCount = prevSession.messages.length;
            const newMessagesCount = session.messages.length;
            if (prevMessagesCount >= newMessagesCount) {
              return prevSession;
            }
          }
          return { ...session, messages: [...session.messages] };
        });
      } else {
        setCurrentSession(null);
      }
    } else {
      setCurrentSession(null);
    }
  }, [currentSessionId, sessionStore]);

  useEffect(() => {
    const allSessions = sessionStore.getAllSessions();
    // Copy: store returns the same array reference it mutates in place; React skips setState if the reference is unchanged.
    setSessions([...allSessions]);
    if (allSessions.length > 0 && !currentSessionId) {
      setCurrentSessionId(allSessions[0].id);
    }
  }, [currentSessionId, sessionStore]);

  const refreshSessions = useCallback(() => {
    const allSessions = sessionStore.getAllSessions();
    setSessions([...allSessions]);
    if (currentSessionId) {
      const updatedSession = sessionStore.getSession(currentSessionId);
      if (updatedSession) {
        setCurrentSession({
          ...updatedSession,
          messages: [...updatedSession.messages],
        });
      }
    }
  }, [sessionStore, currentSessionId]);

  const scrollToBottom = useCallback(() => {
    const el = scrollContainerRef.current;
    if (el && shouldAutoScrollRef.current) el.scrollTop = el.scrollHeight;
  }, []);

  const refreshChainContexts = useCallback(
    async (chainId?: string) => {
      if (!chainContext) return;
      if (chainContext.refresh) await chainContext.refresh();
      if (pageChainContext?.refresh) await pageChainContext.refresh();
      if (
        typeof window !== "undefined" &&
        (chainId ?? chainContext.chain?.id)
      ) {
        const detail = chainId ?? chainContext.chain.id;
        window.dispatchEvent(new CustomEvent("chain-updated", { detail }));
      }
    },
    [chainContext, pageChainContext],
  );

  const handleProgressCompleted = useCallback(
    (toolName: string, progressMsg: string) => {
      const lower = progressMsg.toLowerCase();

      const isCreateChain =
        toolName.includes("catalog.create_chain") ||
        lower.includes("creating chain");
      if (isCreateChain && typeof window !== "undefined") {
        window.dispatchEvent(new CustomEvent("chains-list-refresh"));
      }

      if (!chainContext) return;

      const isChainModification =
        toolName.includes("catalog.create_element") ||
        toolName.includes("catalog.update_element") ||
        toolName.includes("catalog.create_connection") ||
        toolName.includes("catalog.delete_elements") ||
        toolName.includes("catalog.delete_connections") ||
        toolName.includes("catalog.transfer_element") ||
        lower.includes("creating element") ||
        lower.includes("updating element") ||
        lower.includes("creating connection") ||
        lower.includes("deleting elements") ||
        lower.includes("deleting connections") ||
        lower.includes("transferring element");

      if (isChainModification) {
        void refreshChainContexts();
      }
    },
    [chainContext, refreshChainContexts],
  );

  const handleResponseComplete = useCallback(
    (sessionId: string, result: ResponseResult) => {
      const { finalMessages, conversationId } = result;

      sessionStore.updateSessionMessages(sessionId, finalMessages);
      if (conversationId) {
        sessionStore.updateConversationId(sessionId, conversationId);
      }
      refreshSessions();

      const lastAssistant = [...finalMessages]
        .reverse()
        .find((m) => m.role === "assistant");
      if (lastAssistant) {
        const proposal = tryParseChainModificationProposal(
          lastAssistant.content,
        );
        if (proposal) {
          setPendingProposal(proposal);
          setIsConfirmationOpen(true);
        }
      }

      void refreshChainContexts();
    },
    [sessionStore, refreshSessions, refreshChainContexts],
  );

  const runChat = useCallback(
    async (
      aiProvider: AiModelProvider,
      requestPayload: ChatRequest,
      sessionId: string,
      requestMessages: ChatMessage[],
      start: number,
    ): Promise<void> => {
      setIsStreaming(true);

      let accumulatedContent = "";
      let currentMessages = [...requestMessages];

      const onChunk = (chunk: StreamingChunk) => {
        if (chunk.type === "progress" && chunk.progressMessage) {
          accumulatedContent += `\n\n> 💡 ${chunk.progressMessage}\n\n`;
          currentMessages = upsertAssistantMessage(
            currentMessages,
            accumulatedContent,
          );
          sessionStore.updateSessionMessages(sessionId, currentMessages);
          refreshSessions();
          scrollToBottom();

          if (chunk.progressMessage.includes(" - completed")) {
            handleProgressCompleted(
              chunk.toolName ?? "",
              chunk.progressMessage,
            );
          }
        }
        if (chunk.type === "error" && chunk.errorMessage) {
          const errorMsg: ChatMessage = {
            role: "assistant",
            content: chunk.errorMessage,
          };
          sessionStore.updateSessionMessages(sessionId, [
            ...currentMessages,
            errorMsg,
          ]);
          refreshSessions();
        }
      };

      let response: ChatResponse;
      try {
        response = await aiProvider.chatWithProgress(requestPayload, onChunk);
      } finally {
        setIsStreaming(false);
      }

      const durationMs = Math.round(performance.now() - start);

      const responseTail = getResponseTail(requestMessages, response.messages);
      const lastAssistantFromResponse = [...responseTail]
        .reverse()
        .find((m): m is ChatMessage => m.role === "assistant");

      let mergedAssistantContent = accumulatedContent.trim();
      if (lastAssistantFromResponse) {
        const narrative = lastAssistantFromResponse.content
          .replace(/^(\s*>\s*💡[^\n]+(?:\n|$))+/, "")
          .trim();
        if (narrative) {
          mergedAssistantContent = mergedAssistantContent
            ? `${mergedAssistantContent}\n\n${narrative}`
            : narrative;
        }
      }

      let finalMessages: ChatMessage[] = [
        ...requestMessages.filter((m) => (m.role as string) !== "tool"),
        ...(mergedAssistantContent
          ? [{ role: "assistant" as const, content: mergedAssistantContent }]
          : []),
      ];

      if (response.usage || response.finishReason) {
        finalMessages = [
          ...finalMessages,
          buildMetaMessage(durationMs, response.finishReason, response.usage),
        ];
      }

      if (finalMessages.length > 0) {
        handleResponseComplete(sessionId, {
          finalMessages,
          conversationId: response.conversationId,
        });
      }
    },
    [
      sessionStore,
      refreshSessions,
      scrollToBottom,
      handleProgressCompleted,
      handleResponseComplete,
    ],
  );

  const sendToProvider = useCallback(
    async (
      sessionId: string,
      messages: ChatMessage[],
      attachmentUrls?: string[],
      newMessages?: ChatMessage[],
    ) => {
      if (sendInProgressRef.current) {
        console.warn(
          "[AiAssistant] sendToProvider skipped – already in progress",
        );
        return;
      }
      if (messages.length === 0) {
        setProviderError(
          "Conversation is empty. Please type a message and try again.",
        );
        return;
      }
      sendInProgressRef.current = true;

      let aiProvider: AiModelProvider | null = null;
      try {
        aiProvider = getDefaultAiProvider();
        setProviderError(null);
      } catch (error) {
        const errorMsg =
          error instanceof Error
            ? error.message
            : "Failed to initialize AI provider";
        setProviderError(errorMsg);
        const errorMessage: ChatMessage = {
          role: "assistant",
          content: errorMsg,
        };
        sessionStore.updateSessionMessages(sessionId, [
          ...messages,
          errorMessage,
        ]);
        refreshSessions();
        sendInProgressRef.current = false;
        return;
      }

      setIsLoading(true);
      setIsStreaming(false);
      abortControllerRef.current = new AbortController();

      try {
        const currentSessionData = sessionStore.getSession(sessionId);
        const serverConversationId = currentSessionData?.conversationId;
        const messagesToApi =
          serverConversationId && newMessages ? newMessages : messages;

        const requestPayload: ChatRequest = {
          messages: messagesToApi,
          conversationId: serverConversationId,
          abortSignal: abortControllerRef.current.signal,
          attachmentUrls,
          temperature: 1,
        };

        if (chainContext) {
          const { chain, compactSchema } = chainContext;
          requestPayload.context = {
            type: "chain",
            chainId: chain.id,
            compactSchema,
          };
        }

        const start = performance.now();

        await runChat(aiProvider, requestPayload, sessionId, messages, start);
      } catch (error) {
        const message =
          error instanceof Error ? error.message : "Failed to get AI response";
        const lower = message.toLowerCase();
        if (lower.includes("aborted") || lower.includes("cancelled")) {
          sessionStore.updateSessionMessages(sessionId, messages);
          refreshSessions();
          return;
        }
        const errorMessage: ChatMessage = {
          role: "assistant",
          content: message,
        };
        sessionStore.updateSessionMessages(sessionId, [
          ...messages,
          errorMessage,
        ]);
        refreshSessions();
      } finally {
        setIsLoading(false);
        setIsStreaming(false);
        abortControllerRef.current = null;
        sendInProgressRef.current = false;
      }
    },
    [chainContext, sessionStore, refreshSessions, runChat],
  );

  const handleAbort = useCallback(() => {
    abortControllerRef.current?.abort();
  }, []);

  const handleSend = useCallback(async () => {
    const rawValue =
      inputValue || inputRef.current?.resizableTextArea?.textArea?.value || "";
    const messageText = rawValue.trim();
    if ((!messageText && attachedFiles.length === 0) || isLoading) return;

    const sessionId = currentSessionId ?? sessionStore.createSession().id;
    if (sessionId !== currentSessionId) setCurrentSessionId(sessionId);

    const session = sessionStore.getSession(sessionId);
    if (!session) return;

    let attachmentUrls: string[] | undefined;
    if (attachedFiles.length > 0) {
      try {
        const aiProvider = getDefaultAiProvider();
        if (aiProvider.uploadFile) {
          attachmentUrls = (
            await Promise.all(
              attachedFiles.map((file) =>
                aiProvider.uploadFile!(file, sessionId),
              ),
            )
          ).map((r) => r.url);
        }
      } catch (e) {
        console.warn(
          "[AiAssistant] Upload failed, sending without attachments",
          e,
        );
      }
      setAttachedFiles([]);
    }

    const userContent =
      messageText || (attachmentUrls?.length ? "See attached files." : "");
    const userMessage: ChatMessage = { role: "user", content: userContent };
    const next = [...session.messages, userMessage];
    sessionStore.updateSessionMessages(sessionId, next);
    if (attachmentUrls?.length) {
      sessionStore.updateSessionLastAttachmentUrls(sessionId, attachmentUrls);
    }
    setInputValue("");
    refreshSessions();
    await sendToProvider(sessionId, next, attachmentUrls, [userMessage]);

    const after = sessionStore.getSession(sessionId);
    if (
      after &&
      (after.title === "New Chat" || after.title.match(/^Chat \d+$/))
    ) {
      const title =
        userMessage.content.slice(0, 30) +
        (userMessage.content.length > 30 ? "..." : "");
      sessionStore.updateSessionTitle(sessionId, title);
      refreshSessions();
    }
  }, [
    currentSessionId,
    inputValue,
    isLoading,
    attachedFiles,
    refreshSessions,
    sendToProvider,
    sessionStore,
  ]);

  const handleExecutePlanClick = useCallback(async () => {
    if (!currentSessionId || isLoading || isStreaming) return;
    const session = sessionStore.getSession(currentSessionId);
    if (!session) return;
    if (lastUserMessageIsAgree(session.messages)) return;

    const agreeMessage: ChatMessage = { role: "user", content: "Agree" };
    const next = [...session.messages, agreeMessage];
    sessionStore.updateSessionMessages(currentSessionId, next);
    refreshSessions();

    const latestSession = sessionStore.getSession(currentSessionId);
    let attachmentUrls =
      latestSession?.lastAttachmentUrls ?? session.lastAttachmentUrls;
    if (!attachmentUrls?.length) {
      const designUrl = extractDesignUrlFromMessages(session.messages);
      if (designUrl) attachmentUrls = [designUrl];
    }
    await sendToProvider(currentSessionId, next, attachmentUrls, [
      agreeMessage,
    ]);
  }, [
    currentSessionId,
    isLoading,
    isStreaming,
    sessionStore,
    refreshSessions,
    sendToProvider,
  ]);

  const handleClear = useCallback(() => {
    if (isLoading || !currentSessionId) return;
    sessionStore.updateSessionMessages(currentSessionId, []);
    sessionStore.updateConversationId(currentSessionId, undefined);
    refreshSessions();
  }, [isLoading, currentSessionId, sessionStore, refreshSessions]);

  const handlePrepareRegenerateFromIndex = useCallback(
    (messageIndex: number) => {
      if (!currentSessionId) return;
      const session = sessionStore.getSession(currentSessionId);
      if (!session) return;
      const messages = session.messages;
      if (messageIndex < 0 || messageIndex >= messages.length) return;

      let lastUserIndex: number | undefined;
      for (let i = messageIndex; i >= 0; i -= 1) {
        if (messages[i].role === "user") {
          lastUserIndex = i;
          break;
        }
      }
      if (lastUserIndex === undefined) return;

      const userMessage = messages[lastUserIndex];
      sessionStore.updateSessionMessages(
        currentSessionId,
        messages.slice(0, lastUserIndex + 1),
      );
      refreshSessions();
      setInputValue(userMessage.content);
      scrollToBottom();
    },
    [currentSessionId, refreshSessions, sessionStore, scrollToBottom],
  );

  const handleRegenerateFromIndex = useCallback(
    async (messageIndex: number) => {
      if (!currentSessionId || isLoading || isStreaming) return;
      const session = sessionStore.getSession(currentSessionId);
      if (!session) return;
      const messages = session.messages;
      if (messageIndex < 0 || messageIndex >= messages.length) return;

      let lastUserIndex: number | undefined;
      for (let i = messageIndex; i >= 0; i -= 1) {
        if (messages[i].role === "user") {
          lastUserIndex = i;
          break;
        }
      }
      if (lastUserIndex === undefined) return;

      const baseMessages = messages.slice(0, lastUserIndex + 1);
      sessionStore.updateSessionMessages(currentSessionId, baseMessages);
      sessionStore.updateConversationId(currentSessionId, undefined);
      refreshSessions();
      await sendToProvider(currentSessionId, baseMessages);
    },
    [
      currentSessionId,
      isLoading,
      isStreaming,
      refreshSessions,
      sendToProvider,
      sessionStore,
    ],
  );

  const meta = useMemo(() => {
    const msgs = currentSession?.messages ?? [];
    const lastMeta = [...msgs]
      .reverse()
      .find((m) => m.role === "system" && m.content.startsWith("__META__"));
    if (!lastMeta) return null;
    return parseChatMeta(lastMeta.content.replace("__META__", ""));
  }, [currentSession?.messages]);

  const visibleMessages = useMemo(() => {
    return (currentSession?.messages ?? []).filter((m) => {
      if (m.role === "system" && m.content.startsWith("__META__")) return false;
      if (
        m.role === "assistant" &&
        m.content.trim() === "No response from model"
      )
        return false;
      return true;
    });
  }, [currentSession?.messages]);

  const msgs = currentSession?.messages ?? [];
  const lastMessageContentLength =
    msgs.length > 0 ? (msgs[msgs.length - 1]?.content?.length ?? 0) : 0;
  useEffect(() => {
    if (!shouldAutoScrollRef.current) return;
    const el = scrollContainerRef.current;
    if (!el) return;
    const id = requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight;
    });
    return () => cancelAnimationFrame(id);
  }, [currentSession?.messages?.length, lastMessageContentLength, open]);

  const handleScroll = useCallback(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    shouldAutoScrollRef.current =
      el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }, []);

  const ensureSessionOnOpen = useCallback(() => {
    const allSessions = sessionStore.getAllSessions();
    if (allSessions.length === 0) {
      const newSession = sessionStore.createSession();
      setCurrentSessionId(newSession.id);
      refreshSessions();
    }
  }, [sessionStore, refreshSessions]);

  const handleDeleteSession = useCallback(
    (sessionId: string) => {
      sessionStore.deleteSession(sessionId);
      refreshSessions();
      if (currentSessionId === sessionId) {
        const updatedSessions = sessionStore.getAllSessions();
        setCurrentSessionId(
          updatedSessions.length > 0 ? updatedSessions[0].id : null,
        );
      }
    },
    [sessionStore, refreshSessions, currentSessionId],
  );

  const handleTabEdit = useCallback(
    (
      targetKey: string | React.MouseEvent | React.KeyboardEvent,
      action: "add" | "remove",
    ) => {
      if (action === "remove" && typeof targetKey === "string") {
        handleDeleteSession(targetKey);
      }
    },
    [handleDeleteSession],
  );

  const tabItems = useMemo(
    () =>
      sessions.map((session) => ({
        key: session.id,
        label: session.title,
        children: null,
      })),
    [sessions],
  );

  return {
    chainContext,
    assistantName,
    sessions,
    currentSessionId,
    currentSession,
    setCurrentSessionId,
    isLoading,
    isStreaming,
    providerError,
    inputValue,
    setInputValue,
    attachedFiles,
    setAttachedFiles,
    fileInputRef,
    inputRef,
    scrollContainerRef,
    pendingProposal,
    setPendingProposal,
    isConfirmationOpen,
    setIsConfirmationOpen,
    showLongRunningHint,
    workingDots,
    refreshSessions,
    refreshChainContexts,
    handleSend,
    handleAbort,
    handleExecutePlanClick,
    handleClear,
    handlePrepareRegenerateFromIndex,
    handleRegenerateFromIndex,
    handleScroll,
    meta,
    visibleMessages,
    handleCreateSession: () => {
      const newSession = sessionStore.createSession();
      setCurrentSessionId(newSession.id);
      refreshSessions();
    },
    handleSessionChange: (sessionId: string) => setCurrentSessionId(sessionId),
    handleDeleteSession,
    handleTabEdit,
    tabItems,
    ensureSessionOnOpen,
  };
}
