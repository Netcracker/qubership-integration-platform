import { describe, it, expect } from "@jest/globals";
import {
  isCollapsibleChainPlanJsonBlock,
  looksLikeChainImplementationPlanJson,
  discardEmptyAssistantPlaceholder,
  ensureAssistantPlaceholder,
  isOpenAssistantPlaceholder,
  shouldHideEmptyStreamingAssistant,
} from "../../../src/components/ai/chatMessageUtils.ts";
import type { ChatMessage } from "../../../src/ai/modelProviders/types.ts";

describe("looksLikeChainImplementationPlanJson", () => {
  it("detects chain plan shaped JSON", () => {
    const json =
      '{"chain":{"name":"Example"},"elements":[{"clientId":"a","type":"http-trigger"}]}';
    expect(looksLikeChainImplementationPlanJson(json)).toBe(true);
  });

  it("rejects unrelated JSON", () => {
    expect(looksLikeChainImplementationPlanJson('{"debug": true}')).toBe(false);
  });
});

describe("isCollapsibleChainPlanJsonBlock", () => {
  const planJson =
    '{"chain":{"name":"Example"},"elements":[{"clientId":"a","type":"http-trigger"}]}';

  it("returns true for chain-plan-json language tag", () => {
    expect(isCollapsibleChainPlanJsonBlock("chain-plan-json", planJson)).toBe(
      true,
    );
  });

  it("returns true for json language when content looks like a chain plan", () => {
    expect(isCollapsibleChainPlanJsonBlock("json", planJson)).toBe(true);
  });

  it("returns false for regular json blocks", () => {
    expect(isCollapsibleChainPlanJsonBlock("json", '{"visible": true}')).toBe(
      false,
    );
    expect(isCollapsibleChainPlanJsonBlock("typescript", planJson)).toBe(false);
    expect(isCollapsibleChainPlanJsonBlock(undefined, planJson)).toBe(false);
  });
});

describe("assistant placeholder for live activity", () => {
  it("should append a shell when the last message is a user turn", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create a chain" },
    ];
    expect(ensureAssistantPlaceholder(messages)).toEqual([
      { role: "user", content: "Create a chain" },
      { role: "assistant", content: "" },
    ]);
  });

  it("should reuse an in-flight empty assistant shell", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "Create a chain" },
      { role: "assistant", content: "" },
    ];
    expect(ensureAssistantPlaceholder(messages)).toBe(messages);
  });

  it("should append a new shell when the last assistant already has a decision", () => {
    const messages: ChatMessage[] = [
      {
        role: "assistant",
        content: "Approve the brief?",
        decision: {
          id: "gate-1",
          kind: "approve",
          question: "Approve?",
          actions: ["approve"],
        },
      },
    ];
    const next = ensureAssistantPlaceholder(messages);
    expect(next).toHaveLength(2);
    expect(next[1]).toEqual({ role: "assistant", content: "" });
    expect(next[0]).toEqual(messages[0]);
  });

  it("should append a new shell when the last assistant has persisted activity", () => {
    const messages: ChatMessage[] = [
      {
        role: "assistant",
        content: "",
        activity: { steps: [], summary: "1 step", collapsed: true },
      },
    ];
    const next = ensureAssistantPlaceholder(messages);
    expect(next).toHaveLength(2);
    expect(isOpenAssistantPlaceholder(next[1])).toBe(true);
  });

  it("should drop an unused in-flight shell and keep one that received activity", () => {
    const empty: ChatMessage[] = [
      { role: "user", content: "hi" },
      { role: "assistant", content: "" },
    ];
    expect(discardEmptyAssistantPlaceholder(empty)).toEqual([
      { role: "user", content: "hi" },
    ]);

    const withActivity: ChatMessage[] = [
      {
        role: "assistant",
        content: "",
        activity: { steps: [], summary: "1 step", collapsed: true },
      },
    ];
    expect(discardEmptyAssistantPlaceholder(withActivity)).toBe(withActivity);
  });

  it("should keep the last empty assistant while a turn is in flight", () => {
    const shell: ChatMessage = { role: "assistant", content: "" };
    expect(
      shouldHideEmptyStreamingAssistant(shell, {
        isLastVisible: true,
        isTurnInFlight: true,
        hasLiveActivity: true,
      }),
    ).toBe(false);
    expect(
      shouldHideEmptyStreamingAssistant(shell, {
        isLastVisible: false,
        isTurnInFlight: true,
        hasLiveActivity: false,
      }),
    ).toBe(true);
  });
});
