import type { ChatMessage } from "../../ai/modelProviders/types.ts";

export type ServerRoleMessage = ChatMessage & {
  role: "user" | "assistant";
};

function isMetaSystemMessage(message: ChatMessage): boolean {
  return message.role === "system" && message.content.startsWith("__META__");
}

function isErrorVariantMessage(message: ChatMessage): boolean {
  return message.variant === "error";
}

function isVisibleChatMessage(message: ChatMessage): boolean {
  if (isMetaSystemMessage(message)) {
    return false;
  }
  if (message.role === "assistant" && message.content.trim() === "No response from model") {
    return false;
  }
  return message.role === "user" || message.role === "assistant";
}

function isServerRoleMessage(message: ChatMessage): message is ServerRoleMessage {
  if (isErrorVariantMessage(message)) {
    return false;
  }
  return message.role === "user" || message.role === "assistant";
}

export function getVisibleChatMessages(messages: ChatMessage[]): ChatMessage[] {
  return messages.filter(isVisibleChatMessage);
}

/** Server transcript = user + assistant only (no __META__ / error / system placeholders). */
export function toServerMessageList(messages: ChatMessage[]): ServerRoleMessage[] {
  return messages.filter(isServerRoleMessage);
}

/**
 * Map a UI list index of a user bubble to the inclusive server index of that user message.
 */
export function toServerUserIndex(messages: ChatMessage[], uiIndex: number): number {
  const target = messages[uiIndex];
  if (!target || target.role !== "user") {
    throw new Error("UI index is not a user message");
  }

  let serverIndex = 0;
  for (let i = 0; i <= uiIndex; i += 1) {
    const message = messages[i];
    if (isServerRoleMessage(message)) {
      if (i === uiIndex) {
        return serverIndex;
      }
      serverIndex += 1;
    }
  }

  throw new Error("UI index is not a user message");
}

/**
 * Contract A: truncate body afterMessageIndex for Edit/Regenerate on that user bubble.
 */
export function toServerAfterMessageIndex(
  messages: ChatMessage[],
  uiIndex: number,
): number {
  return toServerUserIndex(messages, uiIndex) - 1;
}

export function buildTruncateBody(afterMessageIndex: number): {
  afterMessageIndex: number;
} {
  return { afterMessageIndex };
}

export function visibleToFullMessageIndex(
  fullMessages: ChatMessage[],
  visibleMessages: ChatMessage[],
  visibleIndex: number,
): number {
  const target = visibleMessages[visibleIndex];
  if (!target) {
    return -1;
  }
  return fullMessages.findIndex(
    (message) =>
      message.role === target.role && message.content === target.content,
  );
}

export function findUserIndexAtOrBefore(
  messages: ChatMessage[],
  uiIndex: number,
): number {
  for (let i = uiIndex; i >= 0; i -= 1) {
    if (messages[i]?.role === "user") {
      return i;
    }
  }
  return -1;
}

export function sliceMessagesForEdit(
  messages: ChatMessage[],
  uiIndex: number,
): ChatMessage[] {
  const userIndex = findUserIndexAtOrBefore(messages, uiIndex);
  if (userIndex < 0) {
    return messages;
  }
  return messages.slice(0, userIndex);
}

export function sliceMessagesForRegenerate(
  messages: ChatMessage[],
  uiIndex: number,
): ChatMessage[] {
  const userIndex = findUserIndexAtOrBefore(messages, uiIndex);
  if (userIndex < 0) {
    return messages;
  }
  return messages.slice(0, userIndex + 1);
}

export function nextConversationIdAfterRegenerate(
  conversationId: string | undefined,
): string | undefined {
  return conversationId;
}

export function shouldShowErrorToastForAbort(error: unknown): boolean {
  if (error instanceof DOMException && error.name === "AbortError") {
    return false;
  }
  if (error instanceof Error) {
    const lower = error.message.toLowerCase();
    if (lower.includes("aborted") || lower.includes("cancelled")) {
      return false;
    }
  }
  return true;
}
