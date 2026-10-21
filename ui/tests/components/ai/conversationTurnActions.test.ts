import { describe, it, expect } from "@jest/globals";
import type { ChatMessage } from "../../../src/ai/modelProviders/types.ts";
import { buildMetaMessage } from "../../../src/components/ai/chatMessageUtils.ts";
import {
  buildTruncateBody,
  nextConversationIdAfterRegenerate,
  shouldShowErrorToastForAbort,
  sliceMessagesForEdit,
  sliceMessagesForRegenerate,
  toServerAfterMessageIndex,
  toServerMessageList,
  toServerUserIndex,
  visibleToFullMessageIndex,
  restoreServerMessageIndexes,
  withServerMessageIndex,
} from "../../../src/components/ai/conversationTurnIndex.ts";

const user0: ChatMessage = {
  role: "user",
  content: "hello",
  serverMessageIndex: 0,
};
const assistant0: ChatMessage = { role: "assistant", content: "hi" };
const meta0 = buildMetaMessage(100);
const user1: ChatMessage = {
  role: "user",
  content: "next",
  serverMessageIndex: 2,
};
const assistant1: ChatMessage = { role: "assistant", content: "reply" };

const twoTurnMessages: ChatMessage[] = [
  user0,
  assistant0,
  meta0,
  user1,
  assistant1,
];

describe("buildTruncateBody", () => {
  it("passes through afterMessageIndex", () => {
    expect(buildTruncateBody(3)).toEqual({ afterMessageIndex: 3 });
  });
});

describe("toServerMessageList", () => {
  it("excludes __META__ system rows", () => {
    expect(toServerMessageList(twoTurnMessages)).toEqual([
      user0,
      assistant0,
      user1,
      assistant1,
    ]);
  });
});

describe("toServerAfterMessageIndex", () => {
  it("excludes __META__ between turns for second user", () => {
    const uiIndex = twoTurnMessages.indexOf(user1);
    expect(toServerUserIndex(twoTurnMessages, uiIndex)).toBe(2);
    expect(toServerAfterMessageIndex(twoTurnMessages, uiIndex)).toBe(1);
  });

  it("maps first user to afterMessageIndex -1", () => {
    const uiIndex = twoTurnMessages.indexOf(user0);
    expect(toServerAfterMessageIndex(twoTurnMessages, uiIndex)).toBe(-1);
  });

  it("maps both user bubbles in a two-turn thread with META between", () => {
    const firstUserIndex = twoTurnMessages.indexOf(user0);
    const secondUserIndex = twoTurnMessages.indexOf(user1);
    expect(toServerAfterMessageIndex(twoTurnMessages, firstUserIndex)).toBe(-1);
    expect(toServerAfterMessageIndex(twoTurnMessages, secondUserIndex)).toBe(1);
  });
});

describe("slice helpers", () => {
  it("sliceMessagesForEdit removes edited user and following messages", () => {
    const uiIndex = twoTurnMessages.indexOf(user1);
    expect(sliceMessagesForEdit(twoTurnMessages, uiIndex)).toEqual([
      user0,
      assistant0,
      meta0,
    ]);
  });

  it("sliceMessagesForRegenerate keeps user and drops assistant after", () => {
    const uiIndex = twoTurnMessages.indexOf(user1);
    expect(sliceMessagesForRegenerate(twoTurnMessages, uiIndex)).toEqual([
      user0,
      assistant0,
      meta0,
      user1,
    ]);
  });

  it("edit slice yields one user at position after truncate+replace plan", () => {
    const uiIndex = twoTurnMessages.indexOf(user1);
    const edited = "edited next";
    const afterEdit = [
      ...sliceMessagesForEdit(twoTurnMessages, uiIndex),
      { role: "user" as const, content: edited },
    ];
    const serverUsers = toServerMessageList(afterEdit).filter(
      (m) => m.role === "user",
    );
    expect(serverUsers).toHaveLength(2);
    expect(serverUsers[1].content).toBe(edited);
  });
});

describe("visibleToFullMessageIndex", () => {
  it("selects the second occurrence of an identical user message", () => {
    const messages: ChatMessage[] = [user0, assistant0, { ...user0 }];
    expect(
      visibleToFullMessageIndex(
        messages,
        messages.filter((m) => m.role !== "system"),
        2,
      ),
    ).toBe(2);
  });

  it("selects the last repeated assistant answer through hidden metadata", () => {
    const messages: ChatMessage[] = [
      user0,
      assistant0,
      meta0,
      user1,
      { ...assistant0 },
    ];
    expect(
      visibleToFullMessageIndex(messages, toServerMessageList(messages), 3),
    ).toBe(4);
  });

  it("maps visible index through META filter", () => {
    const visible = toServerMessageList(twoTurnMessages);
    const visibleUser1Index = visible.findIndex(
      (message) => message.role === "user" && message.content === user1.content,
    );
    expect(
      visibleToFullMessageIndex(twoTurnMessages, visible, visibleUser1Index),
    ).toBe(twoTurnMessages.indexOf(user1));
  });
});

it("uses the archived user index after an approval without a user bubble", () => {
  const message = { ...user1, serverMessageIndex: 4 };
  expect(toServerAfterMessageIndex([user0, assistant0, message], 2)).toBe(3);
});

describe("server message positions", () => {
  it.each([2, 4])(
    "uses server history after an answered approval, whether it was archived or rejected: %i",
    (messageIndex) => {
      const messages: ChatMessage[] = [
        { role: "user", content: "first" },
        {
          role: "assistant",
          content: "Approve?",
          decision: {
            id: "gate",
            kind: "approve",
            question: "Approve?",
            actions: ["approve"],
            answeredAction: "approve",
          },
        },
        { role: "user", content: "next" },
      ];
      const restored = restoreServerMessageIndexes(messages, [
        { messageIndex: 0, content: "first" },
        { messageIndex, content: "next" },
      ]);
      expect(toServerAfterMessageIndex(restored, 2)).toBe(messageIndex - 1);
    },
  );

  it("records the latest user input without changing earlier positions", () => {
    const messages = [
      user0,
      assistant0,
      { role: "user" as const, content: "next" },
    ];
    const indexed = withServerMessageIndex(messages, 6);
    expect(toServerAfterMessageIndex(indexed, 2)).toBe(5);
    expect(indexed[0]).toBe(user0);
    expect(messages[2]).not.toHaveProperty("serverMessageIndex");
  });

  it("restores repeated legacy inputs by occurrence while skipping hidden approvals", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "continue" },
      assistant0,
      meta0,
      { role: "user", content: "continue" },
      assistant1,
      { role: "user", content: "approve" },
    ];
    const indexed = restoreServerMessageIndexes(messages, [
      { messageIndex: 0, content: "continue" },
      { messageIndex: 4, content: "continue" },
      { messageIndex: 8, content: "approve" },
    ]);
    expect(toServerAfterMessageIndex(indexed, 3)).toBe(3);
    expect(toServerAfterMessageIndex(indexed, 5)).toBe(7);
    expect(indexed[2]).toBe(meta0);
    expect(messages[0]).not.toHaveProperty("serverMessageIndex");
  });

  it.each([
    [],
    [{ messageIndex: 0, content: "different" }],
    [{ messageIndex: -1, content: "hello" }],
    [{ messageIndex: 0.5, content: "hello" }],
    [{ messageIndex: 2, content: "hello" }],
    [null],
    null,
  ])(
    "rejects mismatched or invalid history instead of guessing positions: %j",
    (positions) => {
      expect(() => restoreServerMessageIndexes([user0], positions)).toThrow(
        "does not match",
      );
    },
  );

  it("requires a server position instead of counting UI bubbles", () => {
    expect(() =>
      toServerAfterMessageIndex([{ role: "user", content: "legacy" }], 0),
    ).toThrow("not available");
  });
});

describe("regenerate conversation id", () => {
  it("keeps conversationId", () => {
    expect(nextConversationIdAfterRegenerate("conv-1")).toBe("conv-1");
  });
});

describe("abort handling", () => {
  it("does not treat abort as error toast", () => {
    expect(
      shouldShowErrorToastForAbort(new DOMException("Aborted", "AbortError")),
    ).toBe(false);
    expect(shouldShowErrorToastForAbort(new Error("network failed"))).toBe(
      true,
    );
  });
});
