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
  if (role === "assistant") return assistantName ?? "Assistant";
  return "System";
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

/** Replace or append the assistant message at the tail of a message array. */
export function upsertAssistantMessage(
  messages: ChatMessage[],
  content: string,
): ChatMessage[] {
  const last = messages[messages.length - 1];
  if (last?.role === "assistant") {
    return [...messages.slice(0, -1), { role: "assistant" as const, content }];
  }
  return [...messages, { role: "assistant" as const, content }];
}
