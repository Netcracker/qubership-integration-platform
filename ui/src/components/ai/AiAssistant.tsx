import { Button, Divider, Drawer, Modal, Space, Tabs, Tag, Typography } from "antd";
import Input from "antd/es/input/index";
import type { TextAreaRef } from "antd/es/input/TextArea";
import React, {
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import "./AiAssistantPanel.css";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { api } from "../../api/api.ts";
import { getConfig } from "../../appConfig.ts";
import { getDefaultAiProvider } from "../../ai/config.ts";
import { getAiServiceUrl } from "../../ai/appConfig.ts";
import { getHeadersForContext } from "../../api/rest/requestHeadersInterceptor.ts";
import type {
  ChatMessage,
  ChatRequest,
  ChatResponse,
  HitlCheckpointPayload,
  StreamingChunk,
} from "../../ai/modelProviders/types.ts";
import { AiModelProvider } from "../../ai/modelProviders/AiModelProvider.ts";
import type { ChatSession } from "../../ai/sessions/types.ts";
import { getChatSessionStore } from "../../ai/sessions/sessionStore.ts";
import { ChainContext as PageChainContext } from "../../pages/ChainPage.tsx";
import type { ChainModificationProposal } from "./ChainModificationConfirmation.tsx";
import { ChainModificationConfirmation } from "./ChainModificationConfirmation.tsx";
import { applyChainModificationProposal } from "./applyChainModificationProposal.ts";
import { useAiDrawerResize } from "./useAiDrawerResize.ts";
import { useChainContext } from "./useChainContext.ts";
import {
  buildMetaMessage,
  getResponseTail,
  parseChatMeta,
  upsertAssistantMessage,
} from "./chatMessageUtils.ts";
import {
  collapseProgressLines,
  parseProgressLines,
  stripInlineProgressSummary,
  stripProgressBlocks,
} from "./aiProgressParsing.ts";
import {
  extractDesignUrlFromMessages,
  lastUserMessageIsBuildChainIntent,
  looksLikeValidationResult,
  replaceChainModificationProposalForDisplay,
  tryParseChainModificationProposal,
} from "./chainModificationContent.ts";
import { getRoleLabel } from "./chatMessageUtils.ts";
import { ExecutionLog } from "./ExecutionLog.tsx";
import { MarkdownRenderer } from "./AiMarkdownRenderer.tsx";
import { WORKING_DOTS, INPUT_TEXTAREA_ROWS, SEND_KEY } from "./aiAssistantConstants.ts";
import type { ChainPlanStatusDto } from "../../api/ai/chainPlanClient.ts";
import {
  approveChainPlanForBuild,
  dismissChainPlanOpenItems,
  fetchChainPlanDetail,
  fetchChainPlanStatus,
} from "../../api/ai/chainPlanClient.ts";
import {
  API_HUB_IMPORT_FOLLOW_UP_MESSAGE,
  IMPORT_SPECIFICATION_SCENARIO_HINT,
  shouldAutoFollowUpImportSpecification,
} from "./apiHubImportHitl.ts";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface ResponseResult {
  finalMessages: ChatMessage[];
  conversationId?: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UI_REFRESH_THROTTLE_MS = 150;

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export const AiAssistant: React.FC = () => {
  const [open, setOpen] = useState(false);

  const sessionStore = getChatSessionStore();
  const chainContext = useChainContext();
  const pageChainContext = useContext(PageChainContext);

  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [currentSession, setCurrentSession] = useState<ChatSession | null>(null);

  const [isLoading, setIsLoading] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [providerError, setProviderError] = useState<string | null>(null);
  const [showLongRunningHint, setShowLongRunningHint] = useState(false);
  const [workingDots, setWorkingDots] = useState(0);

  const [inputValue, setInputValue] = useState("");
  const [attachedFiles, setAttachedFiles] = useState<File[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const inputRef = React.useRef<TextAreaRef | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const sendInProgressRef = useRef(false);
  const pendingImportFollowUpSessionIdRef = useRef<string | null>(null);

  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const shouldAutoScrollRef = useRef(true);

  const lastUiRefreshTimeRef = useRef(0);
  const pendingRefreshRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [pendingProposal, setPendingProposal] = useState<ChainModificationProposal | null>(null);
  const [isConfirmationOpen, setIsConfirmationOpen] = useState(false);

  // HITL
  const [hitlPending, setHitlPending] = useState<
    (HitlCheckpointPayload & { conversationId: string }) | null
  >(null);
  const [hitlCustomAnswer, setHitlCustomAnswer] = useState("");
  const [hitlCustomOpen, setHitlCustomOpen] = useState(false);
  const [hitlSubmitting, setHitlSubmitting] = useState(false);

  // Chain plan
  const [chainPlanStatus, setChainPlanStatus] = useState<ChainPlanStatusDto | null>(null);
  const [chainPlanModalOpen, setChainPlanModalOpen] = useState(false);
  const [chainPlanDetailJson, setChainPlanDetailJson] = useState<string>("");
  const [chainPlanDetailLoading, setChainPlanDetailLoading] = useState(false);

  const assistantName = getConfig().aiAssistantName ?? "Assistant";
  const { drawerWidth, isResizing, onResizeMouseDown } = useAiDrawerResize(open);

  // ---------------------------------------------------------------------------
  // Animations
  // ---------------------------------------------------------------------------

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
    if (!hitlPending) {
      setHitlCustomAnswer("");
      setHitlCustomOpen(false);
    }
  }, [hitlPending]);

  // ---------------------------------------------------------------------------
  // Session management
  // ---------------------------------------------------------------------------

  useEffect(() => {
    if (currentSessionId) {
      const session = sessionStore.getSession(currentSessionId);
      if (session) {
        setCurrentSession((prevSession) => {
          if (prevSession && prevSession.id === currentSessionId) {
            if (prevSession.messages.length >= session.messages.length) {
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
    setSessions([...allSessions]);
    if (allSessions.length > 0 && !currentSessionId) {
      const defaultId = sessionStore.resolveDefaultSessionId(allSessions);
      if (defaultId) setCurrentSessionId(defaultId);
    }
  }, [currentSessionId, sessionStore]);

  const refreshSessions = useCallback(() => {
    const allSessions = sessionStore.getAllSessions();
    setSessions([...allSessions]);
    if (currentSessionId) {
      const updatedSession = sessionStore.getSession(currentSessionId);
      if (updatedSession) {
        setCurrentSession({ ...updatedSession, messages: [...updatedSession.messages] });
      }
    }
  }, [sessionStore, currentSessionId]);

  const throttledRefreshSessions = useCallback(() => {
    const now = Date.now();
    const elapsed = now - lastUiRefreshTimeRef.current;
    if (elapsed >= UI_REFRESH_THROTTLE_MS) {
      lastUiRefreshTimeRef.current = now;
      refreshSessions();
    } else if (pendingRefreshRef.current === null) {
      const delay = UI_REFRESH_THROTTLE_MS - elapsed;
      pendingRefreshRef.current = setTimeout(() => {
        lastUiRefreshTimeRef.current = Date.now();
        pendingRefreshRef.current = null;
        refreshSessions();
      }, delay);
    }
  }, [refreshSessions]);

  const flushRefresh = useCallback(() => {
    if (pendingRefreshRef.current !== null) {
      clearTimeout(pendingRefreshRef.current);
      pendingRefreshRef.current = null;
    }
    lastUiRefreshTimeRef.current = Date.now();
    refreshSessions();
  }, [refreshSessions]);

  const scrollToBottom = useCallback(() => {
    const el = scrollContainerRef.current;
    if (el && shouldAutoScrollRef.current) el.scrollTop = el.scrollHeight;
  }, []);

  // ---------------------------------------------------------------------------
  // Chain plan status
  // ---------------------------------------------------------------------------

  const refreshChainPlanStatus = useCallback(async (conversationId: string | undefined) => {
    if (!conversationId) {
      setChainPlanStatus(null);
      return;
    }
    try {
      const s = await fetchChainPlanStatus(conversationId);
      setChainPlanStatus(s);
    } catch {
      setChainPlanStatus(null);
    }
  }, []);

  useEffect(() => {
    if (!open) {
      setChainPlanStatus(null);
      return;
    }
    void refreshChainPlanStatus(currentSession?.conversationId);
  }, [open, currentSession?.conversationId, refreshChainPlanStatus]);

  // ---------------------------------------------------------------------------
  // Chain context refresh
  // ---------------------------------------------------------------------------

  const refreshChainContexts = useCallback(
    async (chainId?: string) => {
      if (!chainContext) return;
      if (chainContext.refresh) await chainContext.refresh();
      if (pageChainContext?.refresh) await pageChainContext.refresh();
      if (typeof window !== "undefined" && (chainId ?? chainContext.chain?.id)) {
        window.dispatchEvent(
          new CustomEvent("chain-updated", { detail: chainId ?? chainContext.chain.id }),
        );
      }
    },
    [chainContext, pageChainContext],
  );

  const handleProgressCompleted = useCallback(
    (toolName: string, progressMsg: string) => {
      const lower = progressMsg.toLowerCase();
      const isCreateChain =
        toolName.includes("catalog.create_chain") || lower.includes("creating chain");
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
      if (isChainModification) void refreshChainContexts();
    },
    [chainContext, refreshChainContexts],
  );

  // ---------------------------------------------------------------------------
  // Response complete
  // ---------------------------------------------------------------------------

  const handleResponseComplete = useCallback(
    (sessionId: string, result: ResponseResult) => {
      const { finalMessages, conversationId } = result;
      sessionStore.updateSessionMessages(sessionId, finalMessages);
      if (conversationId) sessionStore.updateConversationId(sessionId, conversationId);
      flushRefresh();

      const lastAssistant = [...finalMessages].reverse().find((m) => m.role === "assistant");
      if (lastAssistant) {
        const proposal = tryParseChainModificationProposal(lastAssistant.content);
        if (proposal) {
          setPendingProposal(proposal);
          setIsConfirmationOpen(true);
        }
      }

      void refreshChainContexts();
      const cid = conversationId ?? sessionStore.getSession(sessionId)?.conversationId;
      void refreshChainPlanStatus(cid);
    },
    [sessionStore, flushRefresh, refreshChainContexts, refreshChainPlanStatus],
  );

  // ---------------------------------------------------------------------------
  // Streaming path (with HITL + throttle)
  // ---------------------------------------------------------------------------

  const runStreamingChat = useCallback(
    async (
      aiProvider: AiModelProvider,
      requestPayload: ChatRequest,
      sessionId: string,
      requestMessages: ChatMessage[],
      start: number,
      conversationId: string,
    ): Promise<void> => {
      setIsStreaming(true);

      let accumulatedContent = "";
      let currentMessages = [...requestMessages];
      let periodicalChainRefreshAt = performance.now() + 2000;

      await aiProvider.streamChat!(requestPayload, (chunk: StreamingChunk) => {
        if (chunk.type === "hitl_checkpoint" && chunk.hitlCheckpoint) {
          accumulatedContent += `\n\n> ❓ **${chunk.hitlCheckpoint.question}**\n\n`;
          currentMessages = upsertAssistantMessage(currentMessages, accumulatedContent);
          sessionStore.updateSessionMessages(sessionId, currentMessages);
          refreshSessions();
          scrollToBottom();
          setHitlPending({ ...chunk.hitlCheckpoint, conversationId });
          void refreshChainPlanStatus(conversationId);
          return;
        }

        if (chunk.type === "progress" && chunk.progressMessage) {
          accumulatedContent += `\n\n> 💡 ${chunk.progressMessage}\n\n`;
          currentMessages = upsertAssistantMessage(currentMessages, accumulatedContent);
          sessionStore.updateSessionMessages(sessionId, currentMessages);
          refreshSessions();
          scrollToBottom();
          if (chunk.progressMessage.includes(" - completed")) {
            handleProgressCompleted(chunk.toolName ?? "", chunk.progressMessage);
          }
          return;
        }

        if (chunk.type === "delta" && chunk.contentDelta) {
          accumulatedContent += chunk.contentDelta;
          currentMessages = upsertAssistantMessage(currentMessages, accumulatedContent);
          sessionStore.updateSessionMessages(sessionId, currentMessages);
          throttledRefreshSessions();
          scrollToBottom();
          if (chainContext) {
            const now = performance.now();
            if (now >= periodicalChainRefreshAt) {
              periodicalChainRefreshAt = now + 2000;
              void refreshChainContexts();
            }
          }
          return;
        }

        if (chunk.type === "done") {
          const durationMs = Math.round(performance.now() - start);
          let finalMessages = upsertAssistantMessage(currentMessages, accumulatedContent);
          if (chunk.usage || chunk.finishReason) {
            finalMessages = [
              ...finalMessages,
              buildMetaMessage(durationMs, chunk.finishReason, chunk.usage),
            ];
          }
          setHitlPending(null);
          handleResponseComplete(sessionId, {
            finalMessages,
            conversationId: chunk.conversationId ?? conversationId,
          });
          setIsStreaming(false);
          return;
        }

        if (chunk.type === "error" && chunk.errorMessage) {
          const lower = chunk.errorMessage.toLowerCase();
          setHitlPending(null);
          if (lower.includes("aborted") || lower.includes("cancelled")) {
            setIsStreaming(false);
            return;
          }
          const finalMessages = [
            ...requestMessages,
            { role: "assistant" as const, content: chunk.errorMessage },
          ];
          sessionStore.updateSessionMessages(sessionId, finalMessages);
          flushRefresh();
          setIsStreaming(false);
        }
      });
    },
    [
      chainContext,
      sessionStore,
      refreshSessions,
      throttledRefreshSessions,
      scrollToBottom,
      handleProgressCompleted,
      refreshChainContexts,
      handleResponseComplete,
      flushRefresh,
      refreshChainPlanStatus,
    ],
  );

  // ---------------------------------------------------------------------------
  // chatWithProgress fallback path
  // ---------------------------------------------------------------------------

  const runChatWithProgress = useCallback(
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
          currentMessages = upsertAssistantMessage(currentMessages, accumulatedContent);
          sessionStore.updateSessionMessages(sessionId, currentMessages);
          refreshSessions();
          scrollToBottom();
          if (chunk.progressMessage.includes(" - completed")) {
            handleProgressCompleted(chunk.toolName ?? "", chunk.progressMessage);
          }
        }
        if (chunk.type === "error" && chunk.errorMessage) {
          const errorMsg: ChatMessage = { role: "assistant", content: chunk.errorMessage };
          sessionStore.updateSessionMessages(sessionId, [...currentMessages, errorMsg]);
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

  // ---------------------------------------------------------------------------
  // Plain (non-streaming) fallback
  // ---------------------------------------------------------------------------

  const runPlainChat = useCallback(
    async (
      aiProvider: AiModelProvider,
      requestPayload: ChatRequest,
      sessionId: string,
      requestMessages: ChatMessage[],
      start: number,
    ): Promise<void> => {
      const response = await aiProvider.chat!(requestPayload);
      const durationMs = Math.round(performance.now() - start);

      const responseTail = getResponseTail(requestMessages, response.messages);
      const lastAssistant = [...responseTail]
        .reverse()
        .find((m): m is ChatMessage => m.role === "assistant");

      let finalMessages = lastAssistant
        ? [...requestMessages, lastAssistant]
        : [...requestMessages];
      finalMessages = finalMessages.filter((m) => (m.role as string) !== "tool");

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
    [handleResponseComplete],
  );

  // ---------------------------------------------------------------------------
  // sendToProvider
  // ---------------------------------------------------------------------------

  const sendToProvider = useCallback(
    async (
      sessionId: string,
      messages: ChatMessage[],
      attachmentUrls?: string[],
      newMessages?: ChatMessage[],
      attachmentObjectKeys?: string[],
      scenarioHint?: string,
    ) => {
      if (sendInProgressRef.current) {
        console.warn("[AiAssistant] sendToProvider skipped – already in progress");
        return;
      }
      if (messages.length === 0) {
        setProviderError("Conversation is empty. Please type a message and try again.");
        return;
      }
      sendInProgressRef.current = true;

      let aiProvider: AiModelProvider | null = null;
      try {
        aiProvider = getDefaultAiProvider();
        setProviderError(null);
      } catch (error) {
        const errorMsg =
          error instanceof Error ? error.message : "Failed to initialize AI provider";
        setProviderError(errorMsg);
        const errorMessage: ChatMessage = { role: "assistant", content: errorMsg };
        sessionStore.updateSessionMessages(sessionId, [...messages, errorMessage]);
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
        const messagesToApi = serverConversationId && newMessages ? newMessages : messages;
        const conversationId = serverConversationId ?? crypto.randomUUID();

        // Merge attachment URLs and object keys from previous sends
        const prevUrls = currentSessionData?.lastAttachmentUrls ?? [];
        const incoming = attachmentUrls ?? [];
        const mergedAttachmentUrls =
          prevUrls.length || incoming.length
            ? [...new Set([...prevUrls, ...incoming])]
            : undefined;

        const prevKeys = currentSessionData?.lastAttachmentObjectKeys ?? [];
        const incomingKeys = attachmentObjectKeys ?? [];
        const mergedAttachmentObjectKeys =
          prevKeys.length || incomingKeys.length
            ? [...new Set([...prevKeys, ...incomingKeys])]
            : undefined;

        if (mergedAttachmentObjectKeys?.length) {
          sessionStore.updateSessionLastAttachmentObjectKeys(
            sessionId,
            mergedAttachmentObjectKeys,
          );
        }
        if (mergedAttachmentUrls?.length) {
          sessionStore.updateSessionLastAttachmentUrls(sessionId, mergedAttachmentUrls);
        }

        const requestPayload: ChatRequest = {
          messages: messagesToApi,
          conversationId,
          abortSignal: abortControllerRef.current.signal,
          attachmentUrls: mergedAttachmentUrls,
          attachmentObjectKeys: mergedAttachmentObjectKeys,
          temperature: 1,
          scenarioHint: scenarioHint?.trim() || undefined,
        };

        if (chainContext) {
          const { chain, compactSchema } = chainContext;
          requestPayload.context = { type: "chain", chainId: chain.id, compactSchema };
        }

        const start = performance.now();

        if (aiProvider.capabilities?.supportsStreaming && aiProvider.streamChat) {
          await runStreamingChat(
            aiProvider,
            requestPayload,
            sessionId,
            messages,
            start,
            conversationId,
          );
        } else if (aiProvider.chatWithProgress) {
          await runChatWithProgress(aiProvider, requestPayload, sessionId, messages, start);
        } else {
          await runPlainChat(aiProvider, requestPayload, sessionId, messages, start);
        }
      } catch (error) {
        const message =
          error instanceof Error ? error.message : "Failed to get AI response";
        const lower = message.toLowerCase();
        if (lower.includes("aborted") || lower.includes("cancelled")) {
          sessionStore.updateSessionMessages(sessionId, messages);
          refreshSessions();
          return;
        }
        const errorMessage: ChatMessage = { role: "assistant", content: message };
        sessionStore.updateSessionMessages(sessionId, [...messages, errorMessage]);
        refreshSessions();
      } finally {
        setIsLoading(false);
        setIsStreaming(false);
        abortControllerRef.current = null;
        sendInProgressRef.current = false;
      }
    },
    [
      chainContext,
      sessionStore,
      refreshSessions,
      runStreamingChat,
      runChatWithProgress,
      runPlainChat,
    ],
  );

  const runImportSpecificationFollowUp = useCallback(
    async (sessionId: string) => {
      if (sendInProgressRef.current) return;
      const session = sessionStore.getSession(sessionId);
      if (!session) return;

      const importMessage: ChatMessage = {
        role: "user",
        content: API_HUB_IMPORT_FOLLOW_UP_MESSAGE,
      };
      const next = [...session.messages, importMessage];
      sessionStore.updateSessionMessages(sessionId, next);
      refreshSessions();

      const latestSession = sessionStore.getSession(sessionId);
      await sendToProvider(
        sessionId,
        next,
        latestSession?.lastAttachmentUrls ?? session.lastAttachmentUrls,
        [importMessage],
        latestSession?.lastAttachmentObjectKeys ?? session.lastAttachmentObjectKeys,
        IMPORT_SPECIFICATION_SCENARIO_HINT,
      );
    },
    [sessionStore, refreshSessions, sendToProvider],
  );

  useEffect(() => {
    const sessionId = pendingImportFollowUpSessionIdRef.current;
    if (!sessionId) return;
    if (isLoading || isStreaming || sendInProgressRef.current || hitlSubmitting) return;

    pendingImportFollowUpSessionIdRef.current = null;
    void runImportSpecificationFollowUp(sessionId);
  }, [
    isLoading,
    isStreaming,
    hitlSubmitting,
    runImportSpecificationFollowUp,
  ]);

  // ---------------------------------------------------------------------------
  // Session UI handlers
  // ---------------------------------------------------------------------------

  const showDrawer = useCallback(() => {
    setOpen(true);
    const allSessions = sessionStore.getAllSessions();
    if (allSessions.length === 0) {
      const newSession = sessionStore.createSession();
      setCurrentSessionId(newSession.id);
      refreshSessions();
      return;
    }
    const hasActiveSession =
      currentSessionId !== null && sessionStore.getSession(currentSessionId) !== null;
    if (!hasActiveSession) {
      const defaultId = sessionStore.resolveDefaultSessionId(allSessions);
      if (defaultId) setCurrentSessionId(defaultId);
    }
  }, [sessionStore, refreshSessions, currentSessionId]);

  const onClose = () => setOpen(false);

  const handleCreateSession = () => {
    const newSession = sessionStore.createSession();
    setCurrentSessionId(newSession.id);
    refreshSessions();
  };

  const handleSessionChange = (sessionId: string) => {
    sessionStore.setLastActiveSessionId(sessionId);
    setCurrentSessionId(sessionId);
  };

  const handleDeleteSession = (sessionId: string) => {
    sessionStore.deleteSession(sessionId);
    refreshSessions();
    if (currentSessionId === sessionId) {
      const updatedSessions = sessionStore.getAllSessions();
      setCurrentSessionId(
        updatedSessions.length > 0
          ? sessionStore.resolveDefaultSessionId(updatedSessions)
          : null,
      );
    }
  };

  const handleTabEdit = (
    targetKey: string | React.MouseEvent | React.KeyboardEvent,
    action: "add" | "remove",
  ) => {
    if (action === "remove" && typeof targetKey === "string") {
      handleDeleteSession(targetKey);
    }
  };

  const handleAbort = useCallback(() => {
    abortControllerRef.current?.abort();
  }, []);

  // ---------------------------------------------------------------------------
  // HITL
  // ---------------------------------------------------------------------------

  const handleHitlAnswer = useCallback(
    async (answer: string) => {
      const trimmed = answer.trim();
      if (!hitlPending || !trimmed) return;
      const { checkpointId, conversationId, options } = hitlPending;
      const scheduleImportFollowUp = shouldAutoFollowUpImportSpecification(trimmed, options);
      const serviceUrl = getAiServiceUrl();
      if (!serviceUrl) return;
      const base = serviceUrl.replace(/\/$/, "");
      const path = `/api/v1/chat/${conversationId}/checkpoint/${checkpointId}`;
      const url = `${base}${path}`;
      const rawHeaders = getHeadersForContext({ url, baseURL: base });
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
      };
      if (typeof rawHeaders?.Authorization === "string") {
        headers.Authorization = rawHeaders.Authorization;
      }
      setHitlSubmitting(true);
      try {
        const res = await fetch(url, {
          method: "POST",
          headers,
          body: JSON.stringify({ answer: trimmed }),
        });
        if (!res.ok) {
          const text = await res.text().catch(() => "");
          console.error("[AiAssistant] HITL answer POST failed", res.status, text);
          return;
        }
        if (scheduleImportFollowUp) {
          const sessionId =
            currentSessionId ??
            sessionStore
              .getAllSessions()
              .find((s) => s.conversationId === conversationId)?.id ??
            null;
          if (sessionId) {
            pendingImportFollowUpSessionIdRef.current = sessionId;
          }
        }
        setHitlPending(null);
        setHitlCustomAnswer("");
        setHitlCustomOpen(false);
        void refreshChainPlanStatus(conversationId);
      } catch (err) {
        console.error("[AiAssistant] HITL answer POST failed", err);
      } finally {
        setHitlSubmitting(false);
      }
    },
    [hitlPending, refreshChainPlanStatus, currentSessionId, sessionStore],
  );

  // ---------------------------------------------------------------------------
  // handleSend
  // ---------------------------------------------------------------------------

  const handleSend = useCallback(async () => {
    const rawValue = inputValue || inputRef.current?.resizableTextArea?.textArea?.value || "";
    const messageText = rawValue.trim();
    if ((!messageText && attachedFiles.length === 0) || isLoading || hitlPending) return;

    const sessionId = currentSessionId ?? sessionStore.createSession().id;
    if (sessionId !== currentSessionId) setCurrentSessionId(sessionId);

    const session = sessionStore.getSession(sessionId);
    if (!session) return;

    let attachmentUrls: string[] | undefined;
    let attachmentObjectKeys: string[] | undefined;

    if (attachedFiles.length > 0) {
      try {
        const aiProvider = getDefaultAiProvider();
        if (aiProvider.uploadFile) {
          const results = await Promise.all(
            attachedFiles.map((file) => aiProvider.uploadFile!(file, sessionId)),
          );
          attachmentUrls = results.map((r) => r.url);
          attachmentObjectKeys = results.map((r) => r.objectKey);
        }
      } catch (e) {
        console.warn("[AiAssistant] Upload failed, sending without attachments", e);
      }
      setAttachedFiles([]);
    }

    const userContent =
      messageText ||
      (attachmentObjectKeys?.length ?? attachmentUrls?.length ? "See attached files." : "");
    const userMessage: ChatMessage = { role: "user", content: userContent };
    const next = [...session.messages, userMessage];
    sessionStore.updateSessionMessages(sessionId, next);
    setInputValue("");
    refreshSessions();
    await sendToProvider(sessionId, next, attachmentUrls, [userMessage], attachmentObjectKeys);

    const after = sessionStore.getSession(sessionId);
    if (after && (after.title === "New Chat" || after.title.match(/^Chat \d+$/))) {
      const title =
        userMessage.content.slice(0, 30) + (userMessage.content.length > 30 ? "..." : "");
      sessionStore.updateSessionTitle(sessionId, title);
      refreshSessions();
    }
  }, [
    currentSessionId,
    inputValue,
    isLoading,
    attachedFiles,
    hitlPending,
    refreshSessions,
    sendToProvider,
    sessionStore,
  ]);

  // ---------------------------------------------------------------------------
  // handleBuildChainClick — approve plan, then IMPLEMENT_CHAIN
  // ---------------------------------------------------------------------------

  const handleBuildChainClick = useCallback(async () => {
    if (!currentSessionId || isLoading || isStreaming || hitlPending) return;
    const session = sessionStore.getSession(currentSessionId);
    if (!session) return;
    const conversationId = session.conversationId;
    if (!conversationId) return;
    if (lastUserMessageIsBuildChainIntent(session.messages)) return;

    const runBuild = async () => {
      if ((chainPlanStatus?.openItemCount ?? 0) > 0) {
        const dismissed = await dismissChainPlanOpenItems(conversationId);
        if (dismissed) setChainPlanStatus(dismissed);
      }
      const approved = await approveChainPlanForBuild(conversationId);
      if (approved) setChainPlanStatus(approved);

      const buildMessage: ChatMessage = {
        role: "user",
        content: "Implement the approved chain implementation plan in the catalog.",
      };
      const next = [...session.messages, buildMessage];
      sessionStore.updateSessionMessages(currentSessionId, next);
      refreshSessions();

      const latestSession = sessionStore.getSession(currentSessionId);
      let urls = latestSession?.lastAttachmentUrls ?? session.lastAttachmentUrls;
      const keys =
        latestSession?.lastAttachmentObjectKeys ?? session.lastAttachmentObjectKeys;
      if (!urls?.length) {
        const designUrl = extractDesignUrlFromMessages(session.messages);
        if (designUrl) urls = [designUrl];
      }
      await sendToProvider(
        currentSessionId,
        next,
        urls,
        [buildMessage],
        keys,
        "IMPLEMENT_CHAIN",
      );
    };

    if ((chainPlanStatus?.openItemCount ?? 0) > 0) {
      Modal.confirm({
        title: "Dismiss open plan items?",
        content:
          "Unresolved plan items will be marked dismissed. You can verify bindings in the catalog UI before running the chain.",
        okText: "Dismiss and build",
        cancelText: "Cancel",
        onOk: () => runBuild(),
      });
      return;
    }

    await runBuild();
  }, [
    currentSessionId,
    isLoading,
    isStreaming,
    hitlPending,
    chainPlanStatus,
    sessionStore,
    refreshSessions,
    sendToProvider,
  ]);

  // ---------------------------------------------------------------------------
  // Chain plan modal
  // ---------------------------------------------------------------------------

  const handleOpenChainPlanModal = useCallback(async () => {
    const cid = currentSession?.conversationId;
    if (!cid) return;
    setChainPlanModalOpen(true);
    setChainPlanDetailLoading(true);
    setChainPlanDetailJson("");
    try {
      const detail = await fetchChainPlanDetail(cid);
      setChainPlanDetailJson(detail ? JSON.stringify(detail, null, 2) : "");
    } catch {
      setChainPlanDetailJson("");
    } finally {
      setChainPlanDetailLoading(false);
    }
  }, [currentSession?.conversationId]);

  // ---------------------------------------------------------------------------
  // handleClear
  // ---------------------------------------------------------------------------

  const handleClear = useCallback(() => {
    if (isLoading || !currentSessionId) return;
    sessionStore.updateSessionMessages(currentSessionId, []);
    sessionStore.updateConversationId(currentSessionId, undefined);
    sessionStore.updateSessionLastAttachmentUrls(currentSessionId, undefined);
    sessionStore.updateSessionLastAttachmentObjectKeys(currentSessionId, undefined);
    setChainPlanStatus(null);
    setChainPlanModalOpen(false);
    refreshSessions();
  }, [isLoading, currentSessionId, sessionStore, refreshSessions]);

  // ---------------------------------------------------------------------------
  // Regenerate
  // ---------------------------------------------------------------------------

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
    [currentSessionId, isLoading, isStreaming, refreshSessions, sendToProvider, sessionStore],
  );

  // ---------------------------------------------------------------------------
  // Derived state
  // ---------------------------------------------------------------------------

  const meta = useMemo(() => {
    const msgs = currentSession?.messages ?? [];
    const lastMeta = [...msgs]
      .reverse()
      .find((m) => m.role === "system" && m.content.startsWith("__META__"));
    if (!lastMeta) return null;
    return parseChatMeta(lastMeta.content.replace("__META__", ""));
  }, [currentSession?.messages]);

  const visibleMessages = useMemo(
    () =>
      (currentSession?.messages ?? []).filter((m) => {
        if (m.role === "system" && m.content.startsWith("__META__")) return false;
        if (m.role === "assistant" && m.content.trim() === "No response from model")
          return false;
        return true;
      }),
    [currentSession?.messages],
  );

  const showStreamAbort = (isLoading || isStreaming) && !hitlPending;

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
    shouldAutoScrollRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }, []);

  const tabItems = sessions.map((session) => ({
    key: session.id,
    label: session.title,
    children: null,
  }));

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <>
      <div
        onClick={(e) => {
          e.stopPropagation();
          showDrawer();
        }}
        onMouseDown={(e) => {
          if (e.button === 0) {
            e.stopPropagation();
            showDrawer();
          }
        }}
        style={{ display: "inline-block" }}
      >
        <Button
          type="text"
          aria-label={assistantName}
          title={assistantName}
          style={{ fontSize: 18, color: "inherit" }}
          icon={<OverridableIcon name="comment" />}
          onClick={(e) => {
            e.stopPropagation();
            showDrawer();
          }}
        />
      </div>

      <Drawer
        title={
          <div style={{ width: "100%" }}>
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
              }}
            >
              <span style={{ fontSize: 16, fontWeight: 500 }}>{assistantName}</span>
              <Space size="small">
                <Button
                  size="small"
                  icon={<OverridableIcon name="plus" />}
                  onClick={handleCreateSession}
                >
                  New Chat
                </Button>
                <Button
                  size="small"
                  onClick={handleClear}
                  disabled={isLoading || isStreaming}
                >
                  Clear
                </Button>
              </Space>
            </div>
          </div>
        }
        placement="right"
        open={open}
        closable
        onClose={onClose}
        afterOpenChange={() => {}}
        width={drawerWidth}
        zIndex={2000}
        rootClassName="ai-assistant-drawer"
      >
        <div
          className={`ai-drawer-resize-handle ${isResizing ? "resizing" : ""}`}
          onMouseDown={onResizeMouseDown}
        />

        {sessions.length > 0 && currentSessionId && (
          <Tabs
            activeKey={currentSessionId}
            onChange={handleSessionChange}
            onEdit={handleTabEdit}
            items={tabItems}
            type="editable-card"
            hideAdd
            style={{ marginBottom: 6 }}
            size="small"
          />
        )}

        <div className="ai-chat-root">
          {providerError && (
            <Typography.Text type="danger" className="ai-provider-error">
              {providerError}
            </Typography.Text>
          )}

          {chainContext && (
            <div className="ai-context-pill">
              <span className="ai-context-pill__label">Chain:</span>
              <span className="ai-context-pill__value">{chainContext.chain.name}</span>
            </div>
          )}

          {chainPlanStatus?.hasActivePlan && currentSession?.conversationId && (
            <div
              className="ai-chain-plan-toolbar"
              style={{
                marginBottom: 8,
                display: "flex",
                flexWrap: "wrap",
                gap: 8,
                alignItems: "center",
              }}
            >
              <Button size="small" onClick={() => void handleOpenChainPlanModal()}>
                View plan
              </Button>
              <Button
                size="small"
                type="primary"
                onClick={() => void handleBuildChainClick()}
                disabled={isLoading || isStreaming || Boolean(hitlPending)}
              >
                Build chain
              </Button>
              <Tag color={chainPlanStatus.approved ? "success" : "default"}>
                {chainPlanStatus.approved ? "Approved" : "Draft"}
              </Tag>
              {chainPlanStatus.planId ? (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  planId: {chainPlanStatus.planId}
                </Typography.Text>
              ) : null}
            </div>
          )}

          <div
            ref={scrollContainerRef}
            className="ai-message-list"
            onScroll={handleScroll}
          >
            {visibleMessages.length === 0 ? (
              <div className="ai-empty-state">
                <Typography.Text type="secondary">
                  Ask a question about QIP, chains, services, or elements.
                </Typography.Text>
              </div>
            ) : (
              <>
                {visibleMessages.map((message, index) => {
                  if (
                    message.role === "assistant" &&
                    !message.content.trim() &&
                    (isLoading || isStreaming)
                  ) {
                    return null;
                  }

                  const isUser = message.role === "user";
                  const rawLines =
                    message.role === "assistant"
                      ? parseProgressLines(message.content)
                      : [];
                  let progressLines =
                    message.role === "assistant" ? collapseProgressLines(rawLines) : [];
                  const isLastAssistant =
                    index === visibleMessages.length - 1 && message.role === "assistant";
                  if (isLastAssistant && (isLoading || isStreaming)) {
                    const lastLine = progressLines[progressLines.length - 1];
                    if (lastLine?.status !== "pending") {
                      progressLines = [
                        ...progressLines,
                        {
                          text: `Working${WORKING_DOTS[workingDots]}`,
                          status: "pending" as const,
                        },
                      ];
                    }
                  }

                  const hasProgressLog = progressLines.length > 0;
                  const narrativeContent =
                    message.role === "assistant"
                      ? stripInlineProgressSummary(
                          replaceChainModificationProposalForDisplay(
                            stripProgressBlocks(message.content),
                          ),
                        )
                      : message.content;

                  return (
                    <div
                      key={message.id ?? `msg-${index}`}
                      className={`ai-message ai-message--${message.role}`}
                    >
                      <div className="ai-message__meta">
                        <span className="ai-message__role">
                          {getRoleLabel(message.role, assistantName)}
                        </span>
                      </div>
                      <div className="ai-message__bubble">
                        {hasProgressLog && (
                          <ExecutionLog
                            lines={progressLines}
                            title="Steps"
                            defaultCollapsed={false}
                          />
                        )}
                        {narrativeContent.trim() ? (
                          <MarkdownRenderer>{narrativeContent}</MarkdownRenderer>
                        ) : null}

                        {message.role === "assistant" &&
                          index === visibleMessages.length - 1 &&
                          !isLoading &&
                          !isStreaming &&
                          looksLikeValidationResult(message.content) &&
                          chainContext?.chain?.id && (
                            <div
                              className="ai-message__plan-actions"
                              style={{
                                marginTop: 14,
                                paddingTop: 12,
                                borderTop: "1px solid var(--vscode-border, #eee)",
                              }}
                            >
                              <Button
                                type="primary"
                                size="middle"
                                onClick={() => {
                                  window.location.href = `/chains/${chainContext.chain.id}/sessions`;
                                }}
                              >
                                Go to Sessions
                              </Button>
                            </div>
                          )}

                        {isUser && (
                          <div className="ai-message__actions">
                            <Button
                              size="small"
                              type="text"
                              icon={<OverridableIcon name="edit" />}
                              title="Edit message and send again"
                              onClick={() => handlePrepareRegenerateFromIndex(index)}
                            />
                            <Button
                              size="small"
                              type="text"
                              icon={
                                <OverridableIcon
                                  name="redo"
                                  className="ai-icon-rotate-vertical"
                                />
                              }
                              title="Regenerate from this answer"
                              onClick={() => void handleRegenerateFromIndex(index)}
                            />
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}

                {(isLoading || isStreaming) &&
                  (() => {
                    const lastMessage = visibleMessages[visibleMessages.length - 1];
                    const shouldShowThinking =
                      lastMessage?.role === "user" ||
                      (lastMessage?.role === "assistant" && !lastMessage.content.trim());
                    if (!shouldShowThinking) return null;
                    return (
                      <div className="ai-message ai-message--assistant">
                        <div className="ai-message__meta">
                          <span className="ai-message__role">
                            {getRoleLabel("assistant", assistantName)}
                          </span>
                        </div>
                        <div className="ai-message__bubble">
                          <Typography.Text type="secondary" style={{ fontStyle: "italic" }}>
                            {showLongRunningHint
                              ? "Working… (this may take a minute)"
                              : "Thinking"}
                            <span className="ai-thinking-dots">
                              <span className="ai-thinking-dot ai-thinking-dot--1">.</span>
                              <span className="ai-thinking-dot ai-thinking-dot--2">.</span>
                              <span className="ai-thinking-dot ai-thinking-dot--3">.</span>
                            </span>
                          </Typography.Text>
                        </div>
                      </div>
                    );
                  })()}
              </>
            )}
          </div>

          <Divider className="ai-divider" />

          {hitlPending && (
            <div className="ai-hitl-checkpoint">
              <Typography.Text strong style={{ display: "block", marginBottom: 8 }}>
                {hitlPending.question}
              </Typography.Text>
              <Space size="small" wrap>
                {(hitlPending.options?.length ? hitlPending.options : ["Yes", "No"]).map(
                  (opt) => (
                    <Button
                      key={opt}
                      type="primary"
                      size="small"
                      disabled={hitlSubmitting}
                      onClick={() => void handleHitlAnswer(opt)}
                    >
                      {opt}
                    </Button>
                  ),
                )}
              </Space>
              <div style={{ marginTop: 10 }}>
                <Button
                  type="link"
                  size="small"
                  style={{ paddingLeft: 0 }}
                  onClick={() => setHitlCustomOpen((o) => !o)}
                >
                  {hitlCustomOpen ? "Hide custom answer" : "Type my own answer"}
                </Button>
                {hitlCustomOpen && (
                  <div style={{ marginTop: 8 }}>
                    <Input.TextArea
                      rows={3}
                      value={hitlCustomAnswer}
                      onChange={(e) => setHitlCustomAnswer(e.target.value)}
                      placeholder="e.g. catalog system name, system id, or integration operation ids"
                      disabled={hitlSubmitting}
                    />
                    <Button
                      type="primary"
                      size="small"
                      style={{ marginTop: 8 }}
                      loading={hitlSubmitting}
                      disabled={!hitlCustomAnswer.trim()}
                      onClick={() => void handleHitlAnswer(hitlCustomAnswer)}
                    >
                      Submit custom answer
                    </Button>
                  </div>
                )}
              </div>
            </div>
          )}

          <div className="ai-input">
            <input
              type="file"
              ref={fileInputRef}
              multiple
              accept=".txt,.md,.json,.csv,.pdf,text/plain,application/json,text/markdown,text/csv,application/pdf,image/png,image/jpeg,image/gif,image/webp"
              style={{ display: "none" }}
              onChange={(e) => {
                const files = e.target.files ? Array.from(e.target.files) : [];
                const maxSize = 10 * 1024 * 1024;
                const valid = files.filter((f) => f.size <= maxSize).slice(0, 5);
                setAttachedFiles((prev) => [...prev, ...valid].slice(0, 5));
                e.target.value = "";
              }}
            />

            {attachedFiles.length > 0 && (
              <div
                className="ai-input__attachments"
                style={{
                  marginBottom: 8,
                  display: "flex",
                  flexWrap: "wrap",
                  gap: 6,
                  alignItems: "center",
                }}
              >
                {attachedFiles.map((file, i) => (
                  <span
                    key={`${file.name}-${i}`}
                    style={{
                      fontSize: 12,
                      padding: "2px 8px",
                      background: "var(--vscode-badge-background, #eee)",
                      borderRadius: 4,
                      display: "inline-flex",
                      alignItems: "center",
                      gap: 4,
                    }}
                  >
                    {file.name}
                    <Button
                      type="text"
                      size="small"
                      style={{ padding: 0, minWidth: 20 }}
                      icon={<OverridableIcon name="close" />}
                      onClick={() =>
                        setAttachedFiles((prev) => prev.filter((_, j) => j !== i))
                      }
                      aria-label="Remove attachment"
                    />
                  </span>
                ))}
              </div>
            )}

            <Input.TextArea
              ref={inputRef}
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="Type your message..."
              rows={INPUT_TEXTAREA_ROWS}
              disabled={isLoading || isStreaming || hitlSubmitting}
              onKeyDown={(e) => {
                if (e.key === SEND_KEY && !e.shiftKey) {
                  e.preventDefault();
                  if (!hitlPending) void handleSend();
                }
              }}
            />

            <div className="ai-input__actions">
              <Space size="small">
                <Button
                  type="text"
                  size="small"
                  icon={<OverridableIcon name="paperClip" />}
                  onClick={() => fileInputRef.current?.click()}
                  disabled={isLoading || isStreaming || hitlSubmitting}
                  aria-label="Attach file"
                  title="Attach file"
                />
                {meta?.usage?.totalTokens ? (
                  <Typography.Text type="secondary" className="ai-meta">
                    Tokens: {meta.usage.totalTokens} · {meta.durationMs}ms
                  </Typography.Text>
                ) : null}
                <Button
                  type="primary"
                  className={
                    showStreamAbort
                      ? "ai-send-button ai-send-button--loading"
                      : "ai-send-button"
                  }
                  disabled={hitlSubmitting || (!showStreamAbort && Boolean(hitlPending))}
                  onClick={() => {
                    if (showStreamAbort) {
                      handleAbort();
                    } else {
                      void handleSend();
                    }
                  }}
                >
                  {showStreamAbort && (
                    <OverridableIcon name="redo" style={{ marginRight: 6 }} />
                  )}
                  {showStreamAbort ? "Abort" : "Send"}
                </Button>
              </Space>
            </div>
          </div>
        </div>

        <ChainModificationConfirmation
          open={isConfirmationOpen}
          proposal={pendingProposal}
          onCancel={() => setIsConfirmationOpen(false)}
          onApply={(proposal) => {
            if (!chainContext) {
              setIsConfirmationOpen(false);
              return;
            }
            void applyChainModificationProposal(proposal, api, chainContext)
              .then(() => {
                void refreshChainContexts(proposal.chainId ?? chainContext.chain?.id);
              })
              .catch((err: unknown) => {
                console.error("[AI] Failed to apply chain modifications", err);
              })
              .finally(() => {
                setIsConfirmationOpen(false);
                setPendingProposal(null);
              });
          }}
        />
      </Drawer>

      <Modal
        title="Chain implementation plan"
        open={chainPlanModalOpen}
        onCancel={() => setChainPlanModalOpen(false)}
        footer={
          <Space>
            <Button
              onClick={() => {
                void navigator.clipboard?.writeText(chainPlanDetailJson);
              }}
            >
              Copy JSON
            </Button>
            <Button type="primary" onClick={() => setChainPlanModalOpen(false)}>
              Close
            </Button>
          </Space>
        }
        width="min(920px, 95vw)"
        destroyOnHidden
      >
        {chainPlanDetailLoading ? (
          <Typography.Text type="secondary">Loading…</Typography.Text>
        ) : chainPlanDetailJson ? (
          <pre
            style={{
              maxHeight: 500,
              overflow: "auto",
              fontSize: 12,
              background: "var(--vscode-editor-background, #f5f5f5)",
              padding: 12,
              borderRadius: 4,
            }}
          >
            {chainPlanDetailJson}
          </pre>
        ) : (
          <Typography.Text type="secondary">No plan data.</Typography.Text>
        )}
      </Modal>
    </>
  );
};
