import {
  Button,
  Divider,
  Drawer,
  Modal,
  Space,
  Tabs,
  Tag,
  Typography,
} from "antd";
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
import type {
  ChatDecision,
  ChatMessage,
  ChatRequest,
  ChatResponse,
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
  appendTurnFailure,
  applyStreamingDoneMessages,
  buildMetaMessage,
  discardEmptyAssistantPlaceholder,
  ensureAssistantPlaceholder,
  getResponseTail,
  getRoleLabel,
  parseChatMeta,
  shouldHideEmptyStreamingAssistant,
  upsertAssistantMessage,
  withoutErrorVariantMessages,
} from "./chatMessageUtils.ts";
import {
  appendDecision,
  markDecisionAnswered,
  reconcileDecisionMessages,
  removeDecision,
} from "./chatDecisionUtils.ts";
import { AiDecisionCard } from "./AiDecisionCard.tsx";
import {
  extractDesignUrlFromMessages,
  lastUserMessageIsBuildChainIntent,
  looksLikeValidationResult,
  replaceChainModificationProposalForDisplay,
  tryParseChainModificationProposal,
} from "./chainModificationContent.ts";
import { MarkdownRenderer } from "./AiMarkdownRenderer.tsx";
import { INPUT_TEXTAREA_ROWS, SEND_KEY } from "./aiAssistantConstants.ts";
import { AiActivityInline } from "./activity/AiActivityInline.tsx";
import { attachActivityToLastAssistant } from "./activity/activitySummary.ts";
import { useActivityStore } from "./activity/activityStore.ts";
import {
  resetConversation,
  truncateConversation,
} from "./conversationTurnApi.ts";
import {
  shouldShowErrorToastForAbort,
  getVisibleChatMessages,
  sliceMessagesForEdit,
  sliceMessagesForRegenerate,
  toServerAfterMessageIndex,
  visibleToFullMessageIndex,
} from "./conversationTurnIndex.ts";
import type { ChainPlanStatusDto } from "../../api/ai/chainPlanClient.ts";
import {
  approveChainPlanForBuild,
  dismissChainPlanOpenItems,
  fetchChainPlanDetail,
  fetchChainPlanStatus,
} from "../../api/ai/chainPlanClient.ts";
import { fetchOpenDecision } from "../../api/ai/decisionClient.ts";

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
  const [currentSession, setCurrentSession] = useState<ChatSession | null>(
    null,
  );

  const [isLoading, setIsLoading] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [providerError, setProviderError] = useState<string | null>(null);
  const [showLongRunningHint, setShowLongRunningHint] = useState(false);

  const [inputValue, setInputValue] = useState("");
  const [attachedFiles, setAttachedFiles] = useState<File[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const inputRef = React.useRef<TextAreaRef | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const sendInProgressRef = useRef(false);

  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const shouldAutoScrollRef = useRef(true);

  const lastUiRefreshTimeRef = useRef(0);
  const pendingRefreshRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [pendingProposal, setPendingProposal] =
    useState<ChainModificationProposal | null>(null);
  const [isConfirmationOpen, setIsConfirmationOpen] = useState(false);

  const activityStore = useActivityStore();

  // Chain plan
  const [chainPlanStatus, setChainPlanStatus] =
    useState<ChainPlanStatusDto | null>(null);
  const [chainPlanModalOpen, setChainPlanModalOpen] = useState(false);
  const [chainPlanDetailJson, setChainPlanDetailJson] = useState<string>("");
  const [chainPlanDetailLoading, setChainPlanDetailLoading] = useState(false);

  const assistantName = getConfig().aiAssistantName ?? "Rocky";
  const { drawerWidth, isResizing, onResizeMouseDown } =
    useAiDrawerResize(open);

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

  // ---------------------------------------------------------------------------
  // Session management
  // ---------------------------------------------------------------------------

  useEffect(() => {
    // A session becoming current is the reader looking at it fresh, whatever scroll position a
    // previous session was left at — start following again rather than carrying that over.
    shouldAutoScrollRef.current = true;
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
        setCurrentSession({
          ...updatedSession,
          messages: [...updatedSession.messages],
        });
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

  const refreshChainPlanStatus = useCallback(
    async (conversationId: string | undefined) => {
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
    },
    [],
  );

  useEffect(() => {
    if (!open) {
      setChainPlanStatus(null);
      return;
    }
    void refreshChainPlanStatus(currentSession?.conversationId);
  }, [open, currentSession?.conversationId, refreshChainPlanStatus]);

  // ---------------------------------------------------------------------------
  // Decision reconciliation
  //
  // The decision card is a projection of durable server state, not something the
  // browser remembers: the server is asked what it is waiting on, and the
  // transcript is made to match. A failed fetch is logged and otherwise ignored —
  // it must never be read as "the server has nothing open" and drop a card that
  // is, in fact, still pending.
  // ---------------------------------------------------------------------------

  const reconcileOpenDecision = useCallback(
    async (conversationId: string | undefined, sessionId: string) => {
      if (!conversationId) return;
      // Never project a durable gate onto an in-flight turn: startOrResume can leave a silent
      // WAITING_FOR_INPUT that openGate would otherwise paint as a clarify card while skills run.
      if (sendInProgressRef.current) return;
      let serverDecision: ChatDecision | null;
      try {
        serverDecision = await fetchOpenDecision(conversationId);
      } catch (err) {
        console.warn("[AiAssistant] Failed to reconcile open decision", err);
        return;
      }
      if (sendInProgressRef.current) return;
      const session = sessionStore.getSession(sessionId);
      if (!session) return;
      const reconciled = reconcileDecisionMessages(
        session.messages,
        serverDecision,
      );
      sessionStore.updateSessionMessages(sessionId, reconciled);
      refreshSessions();
    },
    [sessionStore, refreshSessions],
  );

  // Reconcile on mount (for the session the store restores) and whenever the
  // active session switches to one the server tracks a conversation for.
  useEffect(() => {
    if (!currentSessionId) return;
    const conversationId =
      sessionStore.getSession(currentSessionId)?.conversationId;
    if (!conversationId) return;
    void reconcileOpenDecision(conversationId, currentSessionId);
  }, [currentSessionId, sessionStore, reconcileOpenDecision]);

  // ---------------------------------------------------------------------------
  // Chain context refresh
  // ---------------------------------------------------------------------------

  const refreshChainContexts = useCallback(
    async (chainId?: string) => {
      if (!chainContext) return;
      if (chainContext.refresh) await chainContext.refresh();
      if (pageChainContext?.refresh) await pageChainContext.refresh();
      if (
        typeof window !== "undefined" &&
        (chainId ?? chainContext.chain?.id)
      ) {
        window.dispatchEvent(
          new CustomEvent("chain-updated", {
            detail: chainId ?? chainContext.chain.id,
          }),
        );
      }
    },
    [chainContext, pageChainContext],
  );

  // ---------------------------------------------------------------------------
  // Response complete
  // ---------------------------------------------------------------------------

  const handleResponseComplete = useCallback(
    (sessionId: string, result: ResponseResult) => {
      const { finalMessages, conversationId } = result;
      sessionStore.updateSessionMessages(sessionId, finalMessages);
      if (conversationId)
        sessionStore.updateConversationId(sessionId, conversationId);
      flushRefresh();

      const lastAssistant = [...finalMessages]
        .reverse()
        .find((m) => m.role === "assistant" && m.variant !== "error");
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
      const cid =
        conversationId ?? sessionStore.getSession(sessionId)?.conversationId;
      void refreshChainPlanStatus(cid);
      void reconcileOpenDecision(cid, sessionId);
    },
    [
      sessionStore,
      flushRefresh,
      refreshChainContexts,
      refreshChainPlanStatus,
      reconcileOpenDecision,
    ],
  );

  // ---------------------------------------------------------------------------
  // Streaming path (with UI-refresh throttle)
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
      activityStore.reset();

      let accumulatedContent = "";
      let currentMessages = ensureAssistantPlaceholder([...requestMessages]);
      sessionStore.updateSessionMessages(sessionId, currentMessages);
      refreshSessions();
      let periodicalChainRefreshAt = performance.now() + 2000;
      let activeConversationId = conversationId;
      let turnFailed = false;

      await aiProvider.streamChat!(requestPayload, (chunk: StreamingChunk) => {
        if (chunk.type === "meta" && chunk.conversationId) {
          activeConversationId = chunk.conversationId;
          sessionStore.updateConversationId(sessionId, chunk.conversationId);
          return;
        }

        if (chunk.type === "step" && chunk.step) {
          activityStore.applyStep(chunk.step);
          // Activity rows grow inside the scroll container without changing message text.
          scrollToBottom();
          return;
        }

        if (chunk.type === "decision" && chunk.decision) {
          // Keep whatever the assistant streamed before the gate, then park the card after it.
          if (accumulatedContent.trim()) {
            currentMessages = upsertAssistantMessage(
              currentMessages,
              accumulatedContent,
            );
            accumulatedContent = "";
          }
          currentMessages = appendDecision(currentMessages, chunk.decision);
          sessionStore.updateSessionMessages(sessionId, currentMessages);
          refreshSessions();
          scrollToBottom();
          return;
        }

        if (chunk.type === "delta" && chunk.contentDelta) {
          if (turnFailed) {
            return;
          }
          accumulatedContent += chunk.contentDelta;
          currentMessages = upsertAssistantMessage(
            currentMessages,
            accumulatedContent,
          );
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
          let finalMessages = applyStreamingDoneMessages(
            currentMessages,
            accumulatedContent,
            {
              turnFailed,
              durationMs,
              finishReason: chunk.finishReason,
              usage: chunk.usage,
            },
          );
          finalMessages = attachActivityToLastAssistant(
            finalMessages,
            activityStore.getRows(),
            durationMs,
          );
          finalMessages = discardEmptyAssistantPlaceholder(finalMessages);
          activityStore.reset();
          handleResponseComplete(sessionId, {
            finalMessages,
            conversationId: chunk.conversationId ?? activeConversationId,
          });
          setIsStreaming(false);
          scrollToBottom();
          return;
        }

        if (chunk.type === "error" && chunk.errorMessage) {
          // The turn ended without a "done" event: check whether the server still has
          // a gate open so an aborted or failed turn does not leave a stale card.
          void reconcileOpenDecision(activeConversationId, sessionId);
          if (!shouldShowErrorToastForAbort(new Error(chunk.errorMessage))) {
            setIsStreaming(false);
            return;
          }
          turnFailed = true;
          const durationMs = Math.round(performance.now() - start);
          currentMessages = appendTurnFailure(
            currentMessages,
            chunk.errorMessage,
            accumulatedContent,
          );
          currentMessages = attachActivityToLastAssistant(
            currentMessages,
            activityStore.getRows(),
            durationMs,
          );
          currentMessages = discardEmptyAssistantPlaceholder(currentMessages);
          activityStore.reset();
          sessionStore.updateSessionMessages(sessionId, currentMessages);
          flushRefresh();
          setIsStreaming(false);
        }
      });
    },
    [
      chainContext,
      sessionStore,
      activityStore,
      refreshSessions,
      throttledRefreshSessions,
      scrollToBottom,
      refreshChainContexts,
      handleResponseComplete,
      flushRefresh,
      reconcileOpenDecision,
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
      activityStore.reset();
      sessionStore.updateSessionMessages(
        sessionId,
        ensureAssistantPlaceholder(requestMessages),
      );
      refreshSessions();

      const onChunk = (chunk: StreamingChunk) => {
        if (chunk.type === "step" && chunk.step) {
          activityStore.applyStep(chunk.step);
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

      const mergedAssistantContent =
        lastAssistantFromResponse?.content.trim() ?? "";

      let finalMessages: ChatMessage[] = [
        ...requestMessages.filter((m) => (m.role as string) !== "tool"),
        ...(mergedAssistantContent
          ? [{ role: "assistant" as const, content: mergedAssistantContent }]
          : []),
      ];
      if (!mergedAssistantContent) {
        finalMessages = ensureAssistantPlaceholder(finalMessages);
      }

      if (response.usage || response.finishReason) {
        finalMessages = [
          ...finalMessages,
          buildMetaMessage(durationMs, response.finishReason, response.usage),
        ];
      }

      finalMessages = attachActivityToLastAssistant(
        finalMessages,
        activityStore.getRows(),
        durationMs,
      );
      finalMessages = discardEmptyAssistantPlaceholder(finalMessages);
      activityStore.reset();

      if (finalMessages.length > 0) {
        handleResponseComplete(sessionId, {
          finalMessages,
          conversationId: response.conversationId,
        });
      }
    },
    [
      sessionStore,
      activityStore,
      refreshSessions,
      scrollToBottom,
      handleResponseComplete,
    ],
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
      decision?: ChatRequest["decision"],
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
        sessionStore.updateSessionMessages(
          sessionId,
          appendTurnFailure(messages, errorMsg),
        );
        refreshSessions();
        sendInProgressRef.current = false;
        return;
      }

      setIsLoading(true);
      setIsStreaming(false);
      abortControllerRef.current = new AbortController();

      try {
        const currentSessionData = sessionStore.getSession(sessionId);
        const existingConversationId = currentSessionData?.conversationId;
        // Persist before SSE so a failed/aborted stream keeps the same id on retry.
        const conversationId = sessionStore.ensureConversationId(sessionId);
        const messagesToApi =
          existingConversationId && newMessages ? newMessages : messages;

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
          sessionStore.updateSessionLastAttachmentUrls(
            sessionId,
            mergedAttachmentUrls,
          );
        }

        const requestPayload: ChatRequest = {
          messages: withoutErrorVariantMessages(messagesToApi),
          conversationId,
          abortSignal: abortControllerRef.current.signal,
          attachmentUrls: mergedAttachmentUrls,
          attachmentObjectKeys: mergedAttachmentObjectKeys,
          temperature: 1,
          scenarioHint: scenarioHint?.trim() || undefined,
          decision,
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

        if (
          aiProvider.capabilities?.supportsStreaming &&
          aiProvider.streamChat
        ) {
          await runStreamingChat(
            aiProvider,
            requestPayload,
            sessionId,
            messages,
            start,
            conversationId,
          );
        } else {
          await runChatWithProgress(
            aiProvider,
            requestPayload,
            sessionId,
            messages,
            start,
          );
        }
      } catch (error) {
        if (!shouldShowErrorToastForAbort(error)) {
          refreshSessions();
          return;
        }
        const message =
          error instanceof Error ? error.message : "Failed to get AI response";
        const sessionMessages =
          sessionStore.getSession(sessionId)?.messages ?? messages;
        const last = sessionMessages[sessionMessages.length - 1];
        if (last?.variant === "error") {
          refreshSessions();
          return;
        }
        sessionStore.updateSessionMessages(
          sessionId,
          appendTurnFailure(sessionMessages, message),
        );
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
    ],
  );

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
      currentSessionId !== null &&
      sessionStore.getSession(currentSessionId) !== null;
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
    activityStore.markRunningCancelled();
    if (currentSessionId) {
      const session = sessionStore.getSession(currentSessionId);
      const steps = activityStore.getRows();
      if (session) {
        let next = session.messages;
        if (steps.length > 0) {
          next = attachActivityToLastAssistant(next, steps);
        }
        next = discardEmptyAssistantPlaceholder(next);
        if (next !== session.messages) {
          sessionStore.updateSessionMessages(currentSessionId, next);
          refreshSessions();
        }
      }
      // The aborted turn never reaches the "done" or "error" chunk, so nothing else
      // would otherwise check whether the server still has a gate open.
      void reconcileOpenDecision(session?.conversationId, currentSessionId);
    }
    activityStore.reset();
    setIsStreaming(false);
    setIsLoading(false);
  }, [
    activityStore,
    currentSessionId,
    sessionStore,
    refreshSessions,
    reconcileOpenDecision,
  ]);

  // ---------------------------------------------------------------------------
  // Decision card answer
  // ---------------------------------------------------------------------------

  const handleDecisionAnswer = useCallback(
    async (decision: ChatDecision, action: string, comment: string) => {
      if (!currentSessionId || sendInProgressRef.current) return;
      shouldAutoScrollRef.current = true;
      const session = sessionStore.getSession(currentSessionId);
      if (!session) return;

      const answeredMessages = markDecisionAnswered(
        session.messages,
        decision.id,
        action,
      );
      sessionStore.updateSessionMessages(currentSessionId, answeredMessages);
      refreshSessions();

      await sendToProvider(
        currentSessionId,
        answeredMessages,
        session.lastAttachmentUrls,
        [],
        session.lastAttachmentObjectKeys,
        undefined,
        {
          action,
          artifactType: decision.artifactType,
          artifactHash: decision.artifactHash,
          revision: decision.revision,
          comment: comment || undefined,
        },
      );
    },
    [currentSessionId, sessionStore, refreshSessions, sendToProvider],
  );

  // ---------------------------------------------------------------------------
  // handleClarificationSubmit
  // ---------------------------------------------------------------------------

  /**
   * A clarification has no enumerable answer, so the reader's text goes through the ordinary
   * chat-message path — a real user turn, not a decision command — while the card still freezes
   * like an answered approval card.
   */
  const handleClarificationSubmit = useCallback(
    async (decision: ChatDecision, text: string) => {
      if (!currentSessionId || sendInProgressRef.current) return;
      shouldAutoScrollRef.current = true;
      const session = sessionStore.getSession(currentSessionId);
      if (!session) return;

      const answeredLabel = decision.actions.includes(text) ? text : "clarify";
      const answeredMessages = markDecisionAnswered(
        session.messages,
        decision.id,
        answeredLabel,
      );
      const userMessage: ChatMessage = { role: "user", content: text };
      const next = [...answeredMessages, userMessage];
      sessionStore.updateSessionMessages(currentSessionId, next);
      refreshSessions();

      await sendToProvider(
        currentSessionId,
        next,
        session.lastAttachmentUrls,
        [userMessage],
        session.lastAttachmentObjectKeys,
      );
    },
    [currentSessionId, sessionStore, refreshSessions, sendToProvider],
  );

  // ---------------------------------------------------------------------------
  // handleSend
  // ---------------------------------------------------------------------------

  const handleSend = useCallback(async () => {
    const rawValue =
      inputValue || inputRef.current?.resizableTextArea?.textArea?.value || "";
    const messageText = rawValue.trim();
    if ((!messageText && attachedFiles.length === 0) || isLoading) return;
    shouldAutoScrollRef.current = true;

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
            attachedFiles.map((file) =>
              aiProvider.uploadFile!(file, sessionId),
            ),
          );
          attachmentUrls = results.map((r) => r.url);
          attachmentObjectKeys = results.map((r) => r.objectKey);
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
      messageText ||
      ((attachmentObjectKeys?.length ?? attachmentUrls?.length)
        ? "See attached files."
        : "");
    const userMessage: ChatMessage = { role: "user", content: userContent };
    // Drop unanswered cards from a prior wait so a silent bootstrap gate cannot linger
    // above the new turn's skill spinner while the stream is still running.
    let baseMessages = session.messages;
    for (const message of session.messages) {
      const openId =
        message.decision?.answeredAction === undefined
          ? message.decision?.id
          : undefined;
      if (openId) {
        baseMessages = removeDecision(baseMessages, openId);
      }
    }
    const next = [...baseMessages, userMessage];
    sessionStore.updateSessionMessages(sessionId, next);
    setInputValue("");
    refreshSessions();
    await sendToProvider(
      sessionId,
      next,
      attachmentUrls,
      [userMessage],
      attachmentObjectKeys,
    );

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

  // ---------------------------------------------------------------------------
  // handleBuildChainClick — approve plan, then IMPLEMENT_CHAIN
  // ---------------------------------------------------------------------------

  const handleBuildChainClick = useCallback(async () => {
    if (!currentSessionId || isLoading || isStreaming) return;
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
        content:
          "Implement the approved chain implementation plan in the catalog.",
      };
      const next = [...session.messages, buildMessage];
      sessionStore.updateSessionMessages(currentSessionId, next);
      refreshSessions();

      const latestSession = sessionStore.getSession(currentSessionId);
      let urls =
        latestSession?.lastAttachmentUrls ?? session.lastAttachmentUrls;
      const keys =
        latestSession?.lastAttachmentObjectKeys ??
        session.lastAttachmentObjectKeys;
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

  const handleClear = useCallback(async () => {
    if (isLoading || isStreaming || !currentSessionId) return;
    const session = sessionStore.getSession(currentSessionId);
    const conversationId = session?.conversationId;
    const serviceUrl = getAiServiceUrl();

    if (conversationId && serviceUrl) {
      try {
        await resetConversation(serviceUrl, conversationId);
      } catch (err) {
        console.error("[AiAssistant] Clear reset failed", err);
      }
    }

    sessionStore.updateSessionMessages(currentSessionId, []);
    sessionStore.updateSessionLastAttachmentUrls(currentSessionId, undefined);
    sessionStore.updateSessionLastAttachmentObjectKeys(
      currentSessionId,
      undefined,
    );
    activityStore.reset();
    setChainPlanStatus(null);
    setChainPlanModalOpen(false);
    refreshSessions();
  }, [
    isLoading,
    isStreaming,
    currentSessionId,
    sessionStore,
    activityStore,
    refreshSessions,
  ]);

  // ---------------------------------------------------------------------------
  // Regenerate
  // ---------------------------------------------------------------------------

  const handlePrepareRegenerateFromIndex = useCallback(
    async (visibleIndex: number) => {
      if (!currentSessionId || isLoading || isStreaming) return;
      const session = sessionStore.getSession(currentSessionId);
      if (!session) return;

      const visible = getVisibleChatMessages(session.messages);
      const fullIndex = visibleToFullMessageIndex(
        session.messages,
        visible,
        visibleIndex,
      );
      if (fullIndex < 0 || session.messages[fullIndex]?.role !== "user") return;

      const serviceUrl = getAiServiceUrl();
      const conversationId = session.conversationId;
      if (conversationId && serviceUrl) {
        try {
          await truncateConversation(
            serviceUrl,
            conversationId,
            toServerAfterMessageIndex(session.messages, fullIndex),
          );
        } catch (err) {
          console.error("[AiAssistant] Edit truncate failed", err);
          return;
        }
      }

      const sliced = sliceMessagesForEdit(session.messages, fullIndex);
      const userMessage = session.messages[fullIndex];
      sessionStore.updateSessionMessages(currentSessionId, sliced);
      setInputValue(userMessage.content);
      refreshSessions();
      scrollToBottom();
    },
    [
      currentSessionId,
      isLoading,
      isStreaming,
      refreshSessions,
      sessionStore,
      scrollToBottom,
    ],
  );

  const handleRegenerateFromIndex = useCallback(
    async (visibleIndex: number) => {
      if (!currentSessionId || isLoading || isStreaming) return;
      const session = sessionStore.getSession(currentSessionId);
      if (!session) return;

      const visible = getVisibleChatMessages(session.messages);
      const fullIndex = visibleToFullMessageIndex(
        session.messages,
        visible,
        visibleIndex,
      );
      if (fullIndex < 0) return;

      const serviceUrl = getAiServiceUrl();
      const conversationId = session.conversationId;
      if (conversationId && serviceUrl) {
        try {
          await truncateConversation(
            serviceUrl,
            conversationId,
            toServerAfterMessageIndex(session.messages, fullIndex),
          );
        } catch (err) {
          console.error("[AiAssistant] Regenerate truncate failed", err);
          return;
        }
      }

      const baseMessages = sliceMessagesForRegenerate(
        session.messages,
        fullIndex,
      );
      sessionStore.updateSessionMessages(currentSessionId, baseMessages);
      refreshSessions();

      const latestSession = sessionStore.getSession(currentSessionId);
      await sendToProvider(
        currentSessionId,
        baseMessages,
        latestSession?.lastAttachmentUrls ?? session.lastAttachmentUrls,
        undefined,
        latestSession?.lastAttachmentObjectKeys ??
          session.lastAttachmentObjectKeys,
      );
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
    () => getVisibleChatMessages(currentSession?.messages ?? []),
    [currentSession?.messages],
  );

  const showStreamAbort = isLoading || isStreaming;
  const hasActivity = activityStore.rows.length > 0;

  /**
   * Re-pins to the bottom on every real DOM change instead of guessing how long a paint (markdown,
   * syntax highlighting, activity rows, a long historical plan on session switch) takes. A fixed
   * wait — even two animation frames — races that paint and can freeze the view partway down a
   * long message once nothing schedules another attempt.
   */
  useEffect(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    const observer = new MutationObserver(() => {
      if (shouldAutoScrollRef.current) {
        el.scrollTop = el.scrollHeight;
      }
    });
    observer.observe(el, {
      childList: true,
      subtree: true,
      characterData: true,
    });
    return () => observer.disconnect();
    // `open`: the Drawer lazily mounts its body on first open, so scrollContainerRef.current is
    // still null when this effect first runs; re-attaching once it becomes true finds the real node.
  }, [open]);

  const handleScroll = useCallback(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    shouldAutoScrollRef.current =
      el.scrollHeight - el.scrollTop - el.clientHeight < 80;
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
      <div style={{ display: "flex", alignItems: "center" }}>
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
              <span style={{ fontSize: 16, fontWeight: 500 }}>
                {assistantName}
              </span>
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
                  onClick={() => void handleClear()}
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
        // The panel slides in over a CSS transition the scroll-to-bottom effect cannot see;
        // this fires once that transition genuinely finishes, so a restored session lands on its
        // latest message rather than wherever the effect guessed mid-animation.
        afterOpenChange={(nowOpen) => {
          if (nowOpen) scrollToBottom();
        }}
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
              <span className="ai-context-pill__value">
                {chainContext.chain.name}
              </span>
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
              <Button
                size="small"
                onClick={() => void handleOpenChainPlanModal()}
              >
                View plan
              </Button>
              <Button
                size="small"
                type="primary"
                onClick={() => void handleBuildChainClick()}
                disabled={isLoading || isStreaming}
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
                  const isLastVisible = index === visibleMessages.length - 1;
                  const isUser = message.role === "user";
                  const showLiveActivity =
                    message.role === "assistant" &&
                    isLastVisible &&
                    hasActivity;
                  if (
                    shouldHideEmptyStreamingAssistant(message, {
                      isLastVisible,
                      isTurnInFlight: isLoading || isStreaming,
                      hasLiveActivity: showLiveActivity,
                    })
                  ) {
                    return null;
                  }
                  const showPersistedActivity =
                    message.role === "assistant" &&
                    Boolean(message.activity?.steps?.length) &&
                    !showLiveActivity;
                  const isErrorBubble = message.variant === "error";
                  const narrativeContent =
                    message.role === "assistant" && !isErrorBubble
                      ? replaceChainModificationProposalForDisplay(
                          message.content,
                        )
                      : message.content;
                  const showThinkingInBubble =
                    message.role === "assistant" &&
                    !isErrorBubble &&
                    isLastVisible &&
                    (isLoading || isStreaming) &&
                    !narrativeContent.trim() &&
                    !hasActivity &&
                    !showPersistedActivity;

                  return (
                    <div
                      key={message.id ?? `msg-${index}`}
                      className={`ai-message ai-message--${message.role}${
                        isErrorBubble ? " ai-message--error" : ""
                      }`}
                    >
                      <div className="ai-message__meta">
                        <span className="ai-message__role">
                          {getRoleLabel(message.role, assistantName)}
                        </span>
                      </div>
                      <div className="ai-message__bubble">
                        {showLiveActivity ? (
                          <AiActivityInline
                            rows={activityStore.rows}
                            collapsed={false}
                          />
                        ) : null}
                        {showPersistedActivity && message.activity ? (
                          <AiActivityInline
                            rows={message.activity.steps}
                            collapsed={message.activity.collapsed}
                            summary={message.activity.summary}
                          />
                        ) : null}
                        {isErrorBubble ? (
                          <div className="ai-message__error">
                            <Typography.Text type="danger">
                              {message.content}
                            </Typography.Text>
                            {message.detail?.trim() ? (
                              <Typography.Paragraph
                                type="secondary"
                                className="ai-message__error-detail"
                              >
                                {message.detail}
                              </Typography.Paragraph>
                            ) : null}
                          </div>
                        ) : narrativeContent.trim() ? (
                          <MarkdownRenderer>
                            {narrativeContent}
                          </MarkdownRenderer>
                        ) : null}
                        {message.decision ? (
                          <AiDecisionCard
                            decision={message.decision}
                            busy={isLoading || isStreaming}
                            onAnswer={(action, comment) =>
                              void handleDecisionAnswer(
                                message.decision!,
                                action,
                                comment,
                              )
                            }
                            onSubmitClarification={(text) =>
                              void handleClarificationSubmit(
                                message.decision!,
                                text,
                              )
                            }
                          />
                        ) : null}
                        {showThinkingInBubble ? (
                          <Typography.Text
                            type="secondary"
                            style={{ fontStyle: "italic" }}
                          >
                            {showLongRunningHint
                              ? "Working… (this may take a minute)"
                              : "Thinking"}
                            <span className="ai-thinking-dots">
                              <span className="ai-thinking-dot ai-thinking-dot--1">
                                .
                              </span>
                              <span className="ai-thinking-dot ai-thinking-dot--2">
                                .
                              </span>
                              <span className="ai-thinking-dot ai-thinking-dot--3">
                                .
                              </span>
                            </span>
                          </Typography.Text>
                        ) : null}

                        {message.role === "assistant" &&
                          !isErrorBubble &&
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
                                borderTop:
                                  "1px solid var(--vscode-border, #eee)",
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

                        {isUser && !isLoading && !isStreaming && (
                          <div className="ai-message__actions">
                            <Button
                              size="small"
                              type="text"
                              icon={<OverridableIcon name="edit" />}
                              title="Edit message and send again"
                              onClick={() =>
                                void handlePrepareRegenerateFromIndex(index)
                              }
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
                              onClick={() =>
                                void handleRegenerateFromIndex(index)
                              }
                            />
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}

                {(isLoading || isStreaming) &&
                  visibleMessages[visibleMessages.length - 1]?.role ===
                    "user" && (
                    <div className="ai-message ai-message--assistant">
                      <div className="ai-message__meta">
                        <span className="ai-message__role">
                          {getRoleLabel("assistant", assistantName)}
                        </span>
                      </div>
                      <div className="ai-message__bubble">
                        {hasActivity ? (
                          <AiActivityInline
                            rows={activityStore.rows}
                            collapsed={false}
                          />
                        ) : (
                          <Typography.Text
                            type="secondary"
                            style={{ fontStyle: "italic" }}
                          >
                            {showLongRunningHint
                              ? "Working… (this may take a minute)"
                              : "Thinking"}
                            <span className="ai-thinking-dots">
                              <span className="ai-thinking-dot ai-thinking-dot--1">
                                .
                              </span>
                              <span className="ai-thinking-dot ai-thinking-dot--2">
                                .
                              </span>
                              <span className="ai-thinking-dot ai-thinking-dot--3">
                                .
                              </span>
                            </span>
                          </Typography.Text>
                        )}
                      </div>
                    </div>
                  )}
              </>
            )}
          </div>

          <Divider className="ai-divider" />

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
                const valid = files
                  .filter((f) => f.size <= maxSize)
                  .slice(0, 5);
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
                        setAttachedFiles((prev) =>
                          prev.filter((_, j) => j !== i),
                        )
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
              disabled={isLoading || isStreaming}
              onKeyDown={(e) => {
                if (e.key === SEND_KEY && !e.shiftKey) {
                  e.preventDefault();
                  void handleSend();
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
                  disabled={isLoading || isStreaming}
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
                void refreshChainContexts(
                  proposal.chainId ?? chainContext.chain?.id,
                );
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
