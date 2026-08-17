import type { ChatMessage, ChatUsage } from "../../ai/modelProviders/types.ts";

export type ChatMeta = {
  durationMs: number;
  finishReason?: string;
  usage?: ChatUsage;
};

export function parseChatMeta(value: string): ChatMeta | null {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object") return null;
    const meta = parsed as {
      durationMs?: unknown;
      finishReason?: unknown;
      usage?: unknown;
    };
    if (typeof meta.durationMs !== "number") return null;
    const usage =
      meta.usage && typeof meta.usage === "object"
        ? (meta.usage as {
            totalTokens?: unknown;
            inputTokens?: unknown;
            outputTokens?: unknown;
          })
        : undefined;
    const normalizedUsage: ChatUsage | undefined = usage
      ? {
          totalTokens:
            typeof usage.totalTokens === "number"
              ? usage.totalTokens
              : undefined,
          inputTokens:
            typeof usage.inputTokens === "number"
              ? usage.inputTokens
              : undefined,
          outputTokens:
            typeof usage.outputTokens === "number"
              ? usage.outputTokens
              : undefined,
        }
      : undefined;

    return {
      durationMs: meta.durationMs,
      finishReason:
        typeof meta.finishReason === "string" ? meta.finishReason : undefined,
      usage: normalizedUsage,
    };
  } catch {
    return null;
  }
}

export function getRoleLabel(
  role: ChatMessage["role"],
  assistantName?: string,
): string {
  if (role === "user") return "You";
  if (role === "assistant") return assistantName ?? "Rocky";
  return "System";
}

export function looksLikeChainImplementationPlanJson(code: string): boolean {
  const trimmed = code.trim();
  if (!trimmed.startsWith("{") || !trimmed.includes('"elements"')) {
    return false;
  }
  return trimmed.includes('"chain"') || trimmed.includes('"name"');
}

/** Fenced blocks that should render collapsed in chat (expandable by the user). */
export function isCollapsibleChainPlanJsonBlock(
  language: string | undefined,
  code: string,
): boolean {
  if (language === "chain-plan-json") {
    return true;
  }
  return language === "json" && looksLikeChainImplementationPlanJson(code);
}

/** @deprecated use isCollapsibleChainPlanJsonBlock */
export function isHiddenChainPlanJsonLanguage(
  language: string | undefined,
): boolean {
  return language === "chain-plan-json";
}

export function extractMarkdownText(children: unknown): string {
  if (typeof children === "string") return children;
  if (Array.isArray(children)) {
    return children.map((c) => (typeof c === "string" ? c : "")).join("");
  }
  return "";
}

export function getResponseTail(
  requestMessages: ChatMessage[],
  responseMessages: ChatMessage[],
): ChatMessage[] {
  const firstRequest = requestMessages[0];
  const responseStartIndex = firstRequest
    ? responseMessages.findIndex(
        (message) =>
          message.role === firstRequest.role &&
          message.content === firstRequest.content,
      )
    : -1;

  const responseBaseIndex = responseStartIndex >= 0 ? responseStartIndex : 0;
  const maxPrefix = Math.min(
    requestMessages.length,
    responseMessages.length - responseBaseIndex,
  );
  let matchedCount = 0;
  for (; matchedCount < maxPrefix; matchedCount += 1) {
    const req = requestMessages[matchedCount];
    const res = responseMessages[responseBaseIndex + matchedCount];
    if (!req || !res || req.role !== res.role || req.content !== res.content) {
      break;
    }
  }
  return responseMessages.slice(responseBaseIndex + matchedCount);
}

/** Everything sendToProvider needs to do after a successful response. */
export interface ResponseResult {
  finalMessages: ChatMessage[];
  conversationId?: string;
}

export function buildMetaMessage(
  durationMs: number,
  finishReason?: string,
  usage?: ChatUsage,
): ChatMessage {
  return {
    role: "system",
    content: `__META__${JSON.stringify({ durationMs, finishReason, usage })}`,
  };
}

/**
 * True when the trailing assistant bubble is a reserved shell for the in-flight
 * turn: no prose, no gate, and no persisted activity from a prior turn.
 */
export function isOpenAssistantPlaceholder(
  message: ChatMessage | undefined,
): boolean {
  return (
    message?.role === "assistant" &&
    message.variant !== "error" &&
    !message.content.trim() &&
    message.decision === undefined &&
    message.activity === undefined
  );
}

/**
 * Reserve an assistant bubble for the in-flight turn so skill/tool steps have a
 * place to render before any markdown arrives. A finished assistant (prose, a
 * gate, or persisted activity) is left alone and a new shell is appended.
 */
export function ensureAssistantPlaceholder(
  messages: ChatMessage[],
): ChatMessage[] {
  if (isOpenAssistantPlaceholder(messages[messages.length - 1])) {
    return messages;
  }
  return [...messages, { role: "assistant", content: "" }];
}

/** Drop a leftover in-flight shell that never received prose, a gate, or activity. */
export function discardEmptyAssistantPlaceholder(
  messages: ChatMessage[],
): ChatMessage[] {
  if (isOpenAssistantPlaceholder(messages[messages.length - 1])) {
    return messages.slice(0, -1);
  }
  return messages;
}

/**
 * Hide only non-last empty shells while a turn is in flight. The last bubble
 * stays mounted so live skill/tool rows can render before markdown exists.
 */
export function shouldHideEmptyStreamingAssistant(
  message: ChatMessage,
  options: {
    isLastVisible: boolean;
    isTurnInFlight: boolean;
    hasLiveActivity: boolean;
  },
): boolean {
  if (message.role !== "assistant" || message.variant === "error") {
    return false;
  }
  if (message.content.trim() || message.decision || message.activity) {
    return false;
  }
  if (options.hasLiveActivity && options.isLastVisible) {
    return false;
  }
  return options.isTurnInFlight && !options.isLastVisible;
}

/** Replace or append the assistant message at the tail of a message array. */
export function upsertAssistantMessage(
  messages: ChatMessage[],
  content: string,
): ChatMessage[] {
  const last = messages[messages.length - 1];
  if (last?.role === "assistant" && last.variant !== "error") {
    // A trailing empty finalize must not wipe prose (or a decision parked on it).
    if (!content.trim()) {
      return messages;
    }
    return [
      ...messages.slice(0, -1),
      { ...last, role: "assistant" as const, content },
    ];
  }
  return [...messages, { role: "assistant" as const, content }];
}

/** User-facing summary for a failed chat turn (reason lives in `detail`). */
export const TURN_FAILURE_SUMMARY =
  "The AI service failed to complete this reply.";

export function isErrorVariantMessage(message: ChatMessage): boolean {
  return message.variant === "error";
}

export function buildTurnFailureMessage(detail: string): ChatMessage {
  const trimmed = detail.trim();
  return {
    role: "assistant",
    variant: "error",
    content: TURN_FAILURE_SUMMARY,
    ...(trimmed ? { detail: trimmed } : {}),
  };
}

/** Drop display-only error bubbles before sending history to the AI service. */
export function withoutErrorVariantMessages(
  messages: ChatMessage[],
): ChatMessage[] {
  return messages.filter((message) => !isErrorVariantMessage(message));
}

/**
 * Keep any partial assistant reply, then append a turn-failure error bubble.
 */
export function appendTurnFailure(
  messages: ChatMessage[],
  detail: string,
  partialAssistantContent?: string,
): ChatMessage[] {
  let next = [...messages];
  const partial = partialAssistantContent?.trim();
  if (partial) {
    next = upsertAssistantMessage(next, partial);
  } else {
    const last = next[next.length - 1];
    if (
      last?.role === "assistant" &&
      last.variant !== "error" &&
      !last.content.trim()
    ) {
      next = next.slice(0, -1);
    }
  }
  return [...next, buildTurnFailureMessage(detail)];
}

export type StreamingDoneOptions = {
  turnFailed: boolean;
  durationMs: number;
  finishReason?: string;
  usage?: ChatUsage;
};

/**
 * Finalize a streaming turn. When the turn already failed, keep the error bubble
 * and ignore accumulated content / meta from a trailing `done` event.
 */
export function applyStreamingDoneMessages(
  currentMessages: ChatMessage[],
  accumulatedContent: string,
  options: StreamingDoneOptions,
): ChatMessage[] {
  if (options.turnFailed) {
    return currentMessages;
  }
  let finalMessages = accumulatedContent.trim()
    ? upsertAssistantMessage(currentMessages, accumulatedContent)
    : currentMessages;
  if (options.usage || options.finishReason) {
    finalMessages = [
      ...finalMessages,
      buildMetaMessage(options.durationMs, options.finishReason, options.usage),
    ];
  }
  return finalMessages;
}
