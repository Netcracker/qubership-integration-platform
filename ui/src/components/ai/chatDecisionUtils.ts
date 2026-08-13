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
 * Attach a decision to the transcript without wiping surrounding narrative.
 *
 * Prefers the trailing assistant message when it already has prose (so the card
 * sits under the explanation the model just streamed). Otherwise appends a
 * decision-only entry. The server re-issues the same gate on reconnect, so an
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
  if (existingIndex !== -1) {
    return messages.map((message, index) =>
      index === existingIndex ? { ...message, decision } : message,
    );
  }
  const last = messages[messages.length - 1];
  if (
    last?.role === "assistant" &&
    last.variant !== "error" &&
    last.decision === undefined &&
    last.content.trim()
  ) {
    return [...messages.slice(0, -1), { ...last, decision }];
  }
  return [...messages, { role: "assistant", content: "", decision }];
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

/**
 * Reconcile the transcript's decision entries against the single open gate the server currently
 * reports (or `null` for none). The server is the source of truth, so an unanswered entry that no
 * longer matches it is dropped — whether the server reports nothing or a different gate — and a
 * gate the transcript lacks is appended. Answered entries are history and are left untouched.
 */
export function reconcileDecisionMessages(
  messages: ChatMessage[],
  serverDecision: ChatDecision | null,
): ChatMessage[] {
  let result = messages;
  for (const message of messages) {
    const decision = message.decision;
    if (
      decision !== undefined &&
      decision.answeredAction === undefined &&
      decision.id !== serverDecision?.id
    ) {
      result = removeDecision(result, decision.id);
    }
  }
  return serverDecision ? appendDecision(result, serverDecision) : result;
}
