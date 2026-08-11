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
} from "../../../src/components/ai/conversationTurnIndex.ts";
import { buildHitlResumeChatRequest } from "../../../src/components/ai/hitlResume.ts";

const user0: ChatMessage = { role: "user", content: "hello" };
const assistant0: ChatMessage = { role: "assistant", content: "hi" };
const meta0 = buildMetaMessage(100);
const user1: ChatMessage = { role: "user", content: "next" };
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

describe("buildHitlResumeChatRequest", () => {
  it("routes HITL answer through chat POST body", () => {
    expect(
      buildHitlResumeChatRequest({
        conversationId: "conv-1",
        answer: "my answer",
      }),
    ).toEqual({ message: "my answer", conversationId: "conv-1" });
  });
});
