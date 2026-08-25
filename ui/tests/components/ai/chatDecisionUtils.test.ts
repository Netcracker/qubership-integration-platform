/**
 * @jest-environment jsdom
 */
import { beforeEach, describe, expect, it } from "@jest/globals";
import {
  appendDecision,
  decisionCardText,
  findDecision,
  isDecisionMessage,
  markDecisionAnswered,
  reconcileDecisionMessages,
  removeDecision,
  visibleDecisionNarrative,
  visibleMissingEvidence,
} from "../../../src/components/ai/chatDecisionUtils.ts";
import type {
  ChatDecision,
  ChatMessage,
} from "../../../src/ai/modelProviders/types.ts";

function buildDecision(overrides: Partial<ChatDecision> = {}): ChatDecision {
  return {
    id: "gate-1",
    kind: "approve",
    question: "Approve the chain revision?",
    artifactType: "chain",
    artifactHash: "abc123",
    revision: 2,
    actions: ["approve", "request-changes"],
    ...overrides,
  };
}

describe("appendDecision", () => {
  it("should attach the decision to the trailing assistant prose without wiping it", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create the chain" },
      { role: "assistant", content: "Working on it" },
    ];
    const decision = buildDecision();

    const result = appendDecision(messages, decision);

    expect(result).toHaveLength(2);
    expect(result[0]).toEqual(messages[0]);
    expect(result[1].content).toBe("Working on it");
    expect(result[1].decision).toEqual(decision);
    // Original array is untouched.
    expect(messages).toHaveLength(2);
    expect(messages[1].decision).toBeUndefined();
  });

  it("should append a decision-only entry when the trailing message has no prose", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create the chain" },
    ];
    const decision = buildDecision();

    const result = appendDecision(messages, decision);

    expect(result).toHaveLength(2);
    expect(result[1]).toEqual({ role: "assistant", content: "", decision });
  });

  it("should park the decision on an in-flight empty assistant shell", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create the chain" },
      { role: "assistant", content: "" },
    ];
    const decision = buildDecision();

    const result = appendDecision(messages, decision);

    expect(result).toHaveLength(2);
    expect(result[1].content).toBe("");
    expect(result[1].decision).toEqual(decision);
  });

  it("should replace an unanswered entry for the same id instead of duplicating it", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create the chain" },
    ];
    const first = appendDecision(messages, buildDecision());
    const reissued = buildDecision({
      question: "Approve the updated revision?",
    });

    const result = appendDecision(first, reissued);

    const decisionEntries = result.filter(isDecisionMessage);
    expect(decisionEntries).toHaveLength(1);
    expect(decisionEntries[0].decision).toEqual(reissued);
  });

  it("should append a fresh entry when the existing one for the same id is already answered", () => {
    const messages: ChatMessage[] = [];
    const answered = markDecisionAnswered(
      appendDecision(messages, buildDecision()),
      "gate-1",
      "approve",
    );
    const reissued = buildDecision({ question: "Approve again?" });

    const result = appendDecision(answered, reissued);

    expect(result.filter(isDecisionMessage)).toHaveLength(2);
  });
});

describe("markDecisionAnswered", () => {
  it("should set answeredAction on the matching entry and leave the rest untouched", () => {
    const messages = appendDecision(
      [{ role: "assistant", content: "Ready when you are" }],
      buildDecision(),
    );

    const result = markDecisionAnswered(messages, "gate-1", "approve");

    expect(result).toHaveLength(1);
    expect(result[0].content).toBe("Ready when you are");
    expect(result[0].decision?.answeredAction).toBe("approve");
    // Original array is untouched.
    expect(messages[0].decision?.answeredAction).toBeUndefined();
  });

  it("should no-op when the id is absent", () => {
    const messages = appendDecision([], buildDecision());

    const result = markDecisionAnswered(messages, "missing-gate", "approve");

    expect(result).toEqual(messages);
  });
});

describe("removeDecision", () => {
  it("should drop the matching entry", () => {
    const messages = appendDecision(
      [{ role: "user", content: "Hi" }],
      buildDecision(),
    );

    const result = removeDecision(messages, "gate-1");

    expect(result).toEqual([{ role: "user", content: "Hi" }]);
  });

  it("should no-op when the id is absent", () => {
    const messages = appendDecision([], buildDecision());

    const result = removeDecision(messages, "missing-gate");

    expect(result).toEqual(messages);
  });
});

describe("isDecisionMessage / findDecision", () => {
  it("should identify decision entries and find them by id", () => {
    const messages = appendDecision(
      [{ role: "user", content: "Hi" }],
      buildDecision(),
    );

    expect(isDecisionMessage(messages[0])).toBe(false);
    expect(isDecisionMessage(messages[1])).toBe(true);
    expect(findDecision(messages, "gate-1")).toEqual(buildDecision());
    expect(findDecision(messages, "missing-gate")).toBeUndefined();
  });
});

describe("reconcileDecisionMessages", () => {
  it("should append the gate when the server reports one the transcript lacks", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create the chain" },
    ];
    const decision = buildDecision();

    const result = reconcileDecisionMessages(messages, decision);

    const decisionEntries = result.filter(isDecisionMessage);
    expect(decisionEntries).toHaveLength(1);
    expect(decisionEntries[0].decision).toEqual(decision);
  });

  it("should attach a reconciled gate to trailing assistant prose", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create the chain" },
      {
        role: "assistant",
        content: "Here is the requirement brief for HealthProxy.",
      },
    ];
    const decision = buildDecision();

    const result = reconcileDecisionMessages(messages, decision);

    expect(result).toHaveLength(2);
    expect(result[1].content).toContain("HealthProxy");
    expect(result[1].decision).toEqual(decision);
  });

  it("should drop an unanswered card when the server reports no open gate", () => {
    const messages = appendDecision(
      [{ role: "user", content: "Create the chain" }],
      buildDecision(),
    );

    const result = reconcileDecisionMessages(messages, null);

    expect(result.some(isDecisionMessage)).toBe(false);
    expect(result).toEqual([{ role: "user", content: "Create the chain" }]);
  });

  it("should drop a stale unanswered card when the server reports a different gate", () => {
    const messages = appendDecision([], buildDecision({ id: "gate-1" }));
    const nextGate = buildDecision({
      id: "gate-2",
      question: "Approve the next revision?",
    });

    const result = reconcileDecisionMessages(messages, nextGate);

    const decisionEntries = result.filter(isDecisionMessage);
    expect(decisionEntries).toHaveLength(1);
    expect(decisionEntries[0].decision).toEqual(nextGate);
  });

  it("should keep an answered card even when the server reports no open gate", () => {
    const messages = markDecisionAnswered(
      appendDecision(
        [{ role: "user", content: "Create the chain" }],
        buildDecision(),
      ),
      "gate-1",
      "approve",
    );

    const result = reconcileDecisionMessages(messages, null);

    expect(result).toEqual(messages);
  });

  it("should not duplicate the card when the same open gate is fetched again", () => {
    const decision = buildDecision();
    const messages = appendDecision(
      [{ role: "user", content: "Create the chain" }],
      decision,
    );

    const result = reconcileDecisionMessages(messages, decision);

    expect(result.filter(isDecisionMessage)).toHaveLength(1);
    expect(result).toEqual(messages);
  });
});

describe("decision card display helpers", () => {
  const question =
    "What should the new integration chain do? Please provide its trigger.";

  it("should prefer the clarify reason as the card text", () => {
    const decision = buildDecision({
      kind: "clarify",
      question: "fallback question",
      reason: question,
      missingEvidence: [question],
      actions: [],
    });

    expect(decisionCardText(decision)).toBe(question);
  });

  it("should hide missing-evidence rows that already appear as the card text", () => {
    const decision = buildDecision({
      kind: "clarify",
      question,
      reason: question,
      missingEvidence: [question],
      actions: [],
    });

    expect(visibleMissingEvidence(decision)).toEqual([]);
  });

  it("should keep missing-evidence rows that add detail beyond the card text", () => {
    const decision = buildDecision({
      kind: "clarify",
      question: "Some data mappings are still missing.",
      reason: "Some data mappings are still missing.",
      missingEvidence: [
        'INITIALIZATION: ENDPOINT "GET /orders" → SERVICE_CALL "Outbound call call-1"',
      ],
      actions: ["pass_through", "describe_mappings"],
    });

    expect(visibleMissingEvidence(decision)).toEqual(decision.missingEvidence);
  });

  it("should hide assistant prose that only repeats the decision card text", () => {
    const decision = buildDecision({
      kind: "clarify",
      question,
      reason: question,
      missingEvidence: [question],
      actions: [],
    });

    expect(visibleDecisionNarrative(`\n\n${question}\n`, decision)).toBe("");
    expect(
      visibleDecisionNarrative("Here is extra context.\n" + question, decision),
    ).toBe("Here is extra context.\n" + question);
    expect(visibleDecisionNarrative("Working on it", undefined)).toBe(
      "Working on it",
    );
  });
});

describe("decision entry round trip through the session store", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.resetModules();
  });

  it("should survive persistence and reload with the decision intact", async () => {
    jest.useFakeTimers();
    const { getChatSessionStore } = await import(
      "../../../src/ai/sessions/sessionStore.ts"
    );
    const store = getChatSessionStore();
    const session = store.createSession();
    const decision = buildDecision();
    const messages = appendDecision(
      [{ role: "user", content: "Create the chain" }],
      decision,
    );

    store.updateSessionMessages(session.id, messages);
    // updateSessionMessages debounces the localStorage write; flush it.
    jest.advanceTimersByTime(500);
    jest.useRealTimers();

    jest.resetModules();
    const { getChatSessionStore: reloadStore } = await import(
      "../../../src/ai/sessions/sessionStore.ts"
    );
    const reloaded = reloadStore().getSession(session.id);

    const decisionMessage = reloaded?.messages.find(isDecisionMessage);
    expect(decisionMessage?.decision).toEqual(decision);
  });
});
