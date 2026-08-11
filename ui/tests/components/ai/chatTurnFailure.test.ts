import { describe, it, expect } from "@jest/globals";
import type { ChatMessage } from "../../../src/ai/modelProviders/types.ts";
import {
  TURN_FAILURE_SUMMARY,
  appendTurnFailure,
  applyStreamingDoneMessages,
  buildTurnFailureMessage,
  isErrorVariantMessage,
  withoutErrorVariantMessages,
} from "../../../src/components/ai/chatMessageUtils.ts";
import {
  toServerMessageList,
  toServerUserIndex,
} from "../../../src/components/ai/conversationTurnIndex.ts";

describe("buildTurnFailureMessage", () => {
  it("builds an error-variant assistant message with summary and detail", () => {
    const message = buildTurnFailureMessage("Invalid API key");
    expect(message).toEqual({
      role: "assistant",
      variant: "error",
      content: TURN_FAILURE_SUMMARY,
      detail: "Invalid API key",
    });
    expect(isErrorVariantMessage(message)).toBe(true);
  });

  it("omits empty detail", () => {
    const message = buildTurnFailureMessage("   ");
    expect(message.detail).toBeUndefined();
    expect(message.content).toBe(TURN_FAILURE_SUMMARY);
  });
});

describe("withoutErrorVariantMessages", () => {
  it("filters error-variant messages out of the API payload", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "hi" },
      buildTurnFailureMessage("boom"),
      { role: "user", content: "again" },
    ];
    expect(withoutErrorVariantMessages(messages)).toEqual([
      { role: "user", content: "hi" },
      { role: "user", content: "again" },
    ]);
  });
});

describe("appendTurnFailure", () => {
  it("appends only the error message when there is no partial reply", () => {
    const request: ChatMessage[] = [{ role: "user", content: "hi" }];
    expect(appendTurnFailure(request, "Invalid API key")).toEqual([
      { role: "user", content: "hi" },
      buildTurnFailureMessage("Invalid API key"),
    ]);
  });

  it("keeps partial assistant content and appends the error below it", () => {
    const request: ChatMessage[] = [{ role: "user", content: "hi" }];
    expect(appendTurnFailure(request, "rate limited", "Partial answer")).toEqual([
      { role: "user", content: "hi" },
      { role: "assistant", content: "Partial answer" },
      buildTurnFailureMessage("rate limited"),
    ]);
  });

  it("drops an empty trailing assistant before appending the error", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "hi" },
      { role: "assistant", content: "" },
    ];
    expect(appendTurnFailure(messages, "failed")).toEqual([
      { role: "user", content: "hi" },
      buildTurnFailureMessage("failed"),
    ]);
  });
});

describe("applyStreamingDoneMessages", () => {
  it("does not overwrite messages when the turn already failed", () => {
    const failed: ChatMessage[] = [
      { role: "user", content: "hi" },
      buildTurnFailureMessage("Invalid API key"),
    ];
    expect(
      applyStreamingDoneMessages(failed, "", {
        turnFailed: true,
        durationMs: 12,
        finishReason: "error",
      }),
    ).toEqual(failed);
  });

  it("upserts assistant content and meta on a successful done", () => {
    const current: ChatMessage[] = [{ role: "user", content: "hi" }];
    const result = applyStreamingDoneMessages(current, "Hello", {
      turnFailed: false,
      durationMs: 42,
      finishReason: "stop",
    });
    expect(result[0]).toEqual({ role: "user", content: "hi" });
    expect(result[1]).toEqual({ role: "assistant", content: "Hello" });
    expect(result[2]?.role).toBe("system");
    expect(result[2]?.content.startsWith("__META__")).toBe(true);
  });
});

describe("server transcript excludes error variants", () => {
  it("omits error-variant assistants from the server message list", () => {
    const user0: ChatMessage = { role: "user", content: "hello" };
    const error0 = buildTurnFailureMessage("Invalid API key");
    const user1: ChatMessage = { role: "user", content: "retry" };
    const messages = [user0, error0, user1];

    expect(toServerMessageList(messages)).toEqual([user0, user1]);
    expect(toServerUserIndex(messages, messages.indexOf(user1))).toBe(1);
  });
});
