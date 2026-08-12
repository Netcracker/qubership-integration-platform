import type { ChatDecision, ChatMessage } from "../../ai/modelProviders/types.ts";

export function isDecisionMessage(message: ChatMessage): boolean {
  return message.decision !== undefined;
}

export function findDecision(
  messages: ChatMessage[],
  decisionId: string,
): ChatDecision | undefined {
  return messages.find((message) => message.decision?.id === decisionId)
    ?.decision;
}

/**
 * Append a decision as its own transcript entry, keeping every surrounding
 * message untouched. The server re-issues the same gate on reconnect, so an
 * unanswered entry for the same id is replaced in place instead of duplicated;
 * an already-answered entry is left alone and a fresh one is appended.
 */
export function appendDecision(
  messages: ChatMessage[],
  decision: ChatDecision,
): ChatMessage[] {
  const existingIndex = messages.findIndex((message) => {
    const existing = message.decision;
    return (
      existing !== undefined &&
      existing.id === decision.id &&
      existing.answeredAction === undefined
    );
  });
  if (existingIndex === -1) {
    return [...messages, { role: "assistant", content: "", decision }];
  }
  return messages.map((message, index) =>
    index === existingIndex ? { ...message, decision } : message,
  );
}

/** Freeze the entry once the reader answers it; a no-op when the id is absent. */
export function markDecisionAnswered(
  messages: ChatMessage[],
  decisionId: string,
  action: string,
): ChatMessage[] {
  return messages.map((message) => {
    const decision = message.decision;
    if (!decision || decision.id !== decisionId) {
      return message;
    }
    return { ...message, decision: { ...decision, answeredAction: action } };
  });
}

/** Drop the entry the server no longer reports as pending; a no-op when the id is absent. */
export function removeDecision(
  messages: ChatMessage[],
  decisionId: string,
): ChatMessage[] {
  return messages.filter((message) => message.decision?.id !== decisionId);
}
