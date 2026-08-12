/**
 * @jest-environment jsdom
 */
import { beforeEach, describe, expect, it } from "@jest/globals";
import {
  appendDecision,
  findDecision,
  isDecisionMessage,
  markDecisionAnswered,
  removeDecision,
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
  it("should append the decision as its own entry while preserving surrounding messages", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create the chain" },
      { role: "assistant", content: "Working on it" },
    ];
    const decision = buildDecision();

    const result = appendDecision(messages, decision);

    expect(result).toHaveLength(3);
    expect(result[0]).toEqual(messages[0]);
    expect(result[1]).toEqual(messages[1]);
    expect(result[2].decision).toEqual(decision);
    // Original array is untouched.
    expect(messages).toHaveLength(2);
  });

  it("should replace an unanswered entry for the same id instead of duplicating it", () => {
    const messages: ChatMessage[] = [{ role: "user", content: "Create the chain" }];
    const first = appendDecision(messages, buildDecision());
    const reissued = buildDecision({ question: "Approve the updated revision?" });

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

    expect(result[0]).toEqual(messages[0]);
    expect(result[1].decision?.answeredAction).toBe("approve");
    // Original array is untouched.
    expect(messages[1].decision?.answeredAction).toBeUndefined();
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
