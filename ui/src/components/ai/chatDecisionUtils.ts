import type {
  ChatDecision,
  ChatMessage,
} from "../../ai/modelProviders/types.ts";

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
    (Boolean(last.content.trim()) || last.activity === undefined)
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

/** Question shown on the card: clarify prefers `reason`, otherwise `question`. */
export function decisionCardText(decision: ChatDecision): string {
  if (decision.kind === "clarify") {
    return decision.reason?.trim() || decision.question.trim();
  }
  return decision.question.trim();
}

/**
 * Missing-evidence rows that are not already the card question. Discovery often
 * repeats the same open question in `reason` and `missingEvidence`.
 */
export function visibleMissingEvidence(decision: ChatDecision): string[] {
  const items = decision.missingEvidence ?? [];
  if (items.length === 0) {
    return items;
  }
  const cardText = decisionCardText(decision);
  const trimmedItems = items.map((item) => item.trim());
  if (trimmedItems.join("\n") === cardText) {
    return [];
  }
  return items.filter((item) => item.trim() !== cardText);
}

const RECOVERY_ACTION_IDS = new Set([
  "retry-creation",
  "edit-requirements",
  "rebuild-plan",
  "stop-with-report",
]);

function defaultRecoveryActions(
  category: NonNullable<ChatDecision["recovery"]>["category"],
): string[] {
  switch (category) {
    case "temporary-technical-failure":
    case "regeneratable-execution-failure":
      return ["retry-creation", "stop-with-report"];
    case "requirement-brief-defect":
      return ["edit-requirements", "stop-with-report"];
    case "plan-artifact-defect":
      return ["rebuild-plan", "stop-with-report"];
    default:
      return ["stop-with-report"];
  }
}

/** Labeled halt actions, or the category default when the wire list is empty. */
export function recoveryCardActions(decision: ChatDecision): string[] {
  if (!decision.recovery) {
    return [];
  }
  const labeled = (decision.actions ?? []).filter((action) =>
    RECOVERY_ACTION_IDS.has(action),
  );
  if (labeled.length > 0) {
    return labeled;
  }
  return defaultRecoveryActions(decision.recovery.category);
}

export function hasUnansweredDecision(messages: ChatMessage[]): boolean {
  return messages.some(
    (message) =>
      message.decision !== undefined &&
      message.decision.answeredAction === undefined,
  );
}

export function isActionableDecision(decision: ChatDecision): boolean {
  return decision.answeredAction === undefined;
}

/** Opening seed for a closed run: first user text, not a later clarify packet. */
export function openingUserAssignment(messages: ChatMessage[]): string {
  const first = messages.find(
    (message) => message.role === "user" && Boolean(message.content.trim()),
  );
  return first?.content.trim() ?? "";
}

/** Empty clarify with no question is a halt, not a dead Submit. */
export function isBlankClarifyHalt(decision: ChatDecision): boolean {
  return (
    decision.kind === "clarify" &&
    decision.recovery === undefined &&
    (decision.actions?.length ?? 0) === 0 &&
    !decisionCardText(decision)
  );
}

/**
 * Assistant prose to show above a decision card. Empty when the prose only
 * repeats the card question, so the transcript does not print it twice.
 */
export function visibleDecisionNarrative(
  content: string,
  decision: ChatDecision | undefined,
): string {
  if (!decision) {
    return content;
  }
  const trimmed = content.trim();
  if (!trimmed) {
    return content;
  }
  const cardText = decisionCardText(decision);
  const question = decision.question.trim();
  const reason = decision.reason?.trim() ?? "";
  if (
    trimmed === cardText ||
    trimmed === question ||
    (reason !== "" && trimmed === reason)
  ) {
    return "";
  }
  return content;
}
