/**
 * @jest-environment jsdom
 */
import { beforeEach, describe, expect, it } from "@jest/globals";
import { restoreServerMessageIndexes } from "../../src/components/ai/conversationTurnIndex.ts";

describe("ChatSessionStore conversationId (A1)", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.resetModules();
  });

  async function loadStore() {
    const mod = await import("../../src/ai/sessions/sessionStore.ts");
    return mod.getChatSessionStore();
  }

  const legacySession = {
    id: "legacy",
    title: "Saved chat",
    mode: "ask",
    createdAt: 1,
    updatedAt: 2,
    conversationId: "saved-conversation",
    messages: [
      {
        id: "user-1",
        role: "user",
        content: "continue",
        serverMessageIndex: 6,
      },
    ],
    lastAttachmentObjectKeys: ["saved-attachment"],
  };

  it("migrates legacy chats and the active session without changing their contents", async () => {
    localStorage.setItem(
      "qip_ai_chat_sessions",
      JSON.stringify([legacySession]),
    );
    localStorage.setItem("qip_ai_last_active_session_id", legacySession.id);

    const store = await loadStore();
    expect(store.getSession(legacySession.id)).toEqual(legacySession);
    expect(store.resolveDefaultSessionId(store.getAllSessions())).toBe(
      legacySession.id,
    );
    expect(JSON.parse(localStorage.getItem("cip_ai_chat_sessions")!)).toEqual([
      legacySession,
    ]);
    expect(localStorage.getItem("cip_ai_last_active_session_id")).toBe(
      legacySession.id,
    );
    expect(localStorage.getItem("qip_ai_chat_sessions")).toBeNull();
    expect(localStorage.getItem("qip_ai_last_active_session_id")).toBeNull();

    jest.resetModules();
    expect((await loadStore()).getSession(legacySession.id)).toEqual(
      legacySession,
    );
  });

  it("keeps current CIP chats and their active session when merging legacy history", async () => {
    const current = {
      ...legacySession,
      title: "Current chat",
      conversationId: "current-conversation",
    };
    const additional = { ...legacySession, id: "another-legacy-chat" };
    localStorage.setItem(
      "qip_ai_chat_sessions",
      JSON.stringify([legacySession, additional]),
    );
    localStorage.setItem("qip_ai_last_active_session_id", additional.id);
    localStorage.setItem("cip_ai_chat_sessions", JSON.stringify([current]));
    localStorage.setItem("cip_ai_last_active_session_id", current.id);

    const store = await loadStore();
    expect(store.getAllSessions()).toEqual([current, additional]);
    expect(store.resolveDefaultSessionId(store.getAllSessions())).toBe(
      current.id,
    );
  });

  it("imports legacy chats when current CIP history is empty and keeps deletions after reload", async () => {
    localStorage.setItem(
      "qip_ai_chat_sessions",
      JSON.stringify([legacySession]),
    );
    localStorage.setItem("cip_ai_chat_sessions", "[]");
    const store = await loadStore();
    expect(store.getSession(legacySession.id)).toEqual(legacySession);
    store.deleteSession(legacySession.id);

    jest.resetModules();
    expect((await loadStore()).getAllSessions()).toEqual([]);
  });

  it("keeps legacy history when storage rejects the migration write", async () => {
    const serialized = JSON.stringify([legacySession]);
    localStorage.setItem("qip_ai_chat_sessions", serialized);
    const write = jest
      .spyOn(Storage.prototype, "setItem")
      .mockImplementationOnce(() => {
        throw new DOMException("Storage is full", "QuotaExceededError");
      });
    try {
      await loadStore();
      expect(write).toHaveBeenCalled();
      expect(localStorage.getItem("qip_ai_chat_sessions")).toBe(serialized);
      expect(localStorage.getItem("cip_ai_chat_sessions")).toBeNull();
    } finally {
      write.mockRestore();
    }

    jest.resetModules();
    expect((await loadStore()).getSession(legacySession.id)).toEqual(
      legacySession,
    );
  });

  it("backfills a saved legacy session with repeated inputs and persists their positions", async () => {
    jest.useFakeTimers();
    try {
      localStorage.setItem(
        "qip_ai_chat_sessions",
        JSON.stringify([
          {
            id: "legacy",
            title: "Old conversation",
            mode: "ask",
            createdAt: 1,
            updatedAt: 1,
            conversationId: "c",
            messages: [
              { role: "user", content: "continue" },
              { role: "assistant", content: "answer" },
              { role: "user", content: "continue" },
            ],
          },
        ]),
      );
      const store = await loadStore();
      const messages = store.getSession("legacy")!.messages;
      expect(messages[2].serverMessageIndex).toBeUndefined();
      store.updateSessionMessages(
        "legacy",
        restoreServerMessageIndexes(messages, [
          { messageIndex: 0, content: "continue" },
          { messageIndex: 6, content: "continue" },
        ]),
      );
      jest.runOnlyPendingTimers();
      jest.resetModules();
      const restored = await loadStore();
      expect(
        restored
          .getSession("legacy")!
          .messages.filter((m) => m.role === "user")
          .map((m) => m.serverMessageIndex),
      ).toEqual([0, 6]);
    } finally {
      jest.useRealTimers();
    }
  });

  it("restores server positions after saving and reloading a session", async () => {
    jest.useFakeTimers();
    try {
      const store = await loadStore();
      const session = store.createSession();
      store.updateSessionMessages(session.id, [
        { role: "user", content: "continue", serverMessageIndex: 6 },
      ]);
      jest.runOnlyPendingTimers();
      jest.resetModules();
      const restored = await loadStore();
      expect(
        restored.getSession(session.id)?.messages[0].serverMessageIndex,
      ).toBe(6);
    } finally {
      jest.useRealTimers();
    }
  });

  it("should mint and persist conversationId before first send when missing", async () => {
    const store = await loadStore();
    const session = store.createSession();
    expect(session.conversationId).toBeUndefined();

    const first = store.ensureConversationId(session.id);
    expect(first).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(store.getSession(session.id)?.conversationId).toBe(first);
  });

  it("should return the same conversationId on repeated ensure for one session", async () => {
    const store = await loadStore();
    const session = store.createSession();
    const first = store.ensureConversationId(session.id);
    const second = store.ensureConversationId(session.id);
    expect(second).toBe(first);
  });

  it("should keep distinct conversationIds for distinct sessions (New Chat)", async () => {
    const store = await loadStore();
    const a = store.createSession();
    const b = store.createSession();
    const idA = store.ensureConversationId(a.id);
    const idB = store.ensureConversationId(b.id);
    expect(idA).not.toBe(idB);
    expect(store.getSession(a.id)?.conversationId).toBe(idA);
    expect(store.getSession(b.id)?.conversationId).toBe(idB);
  });

  it("should restore conversationId from localStorage after remount", async () => {
    const store = await loadStore();
    const session = store.createSession();
    const conversationId = store.ensureConversationId(session.id);
    const sessionId = session.id;

    jest.resetModules();
    const remounted = await loadStore();
    expect(remounted.getSession(sessionId)?.conversationId).toBe(
      conversationId,
    );
    expect(remounted.ensureConversationId(sessionId)).toBe(conversationId);
  });

  it("should throw when session id is unknown", async () => {
    const store = await loadStore();
    expect(() => store.ensureConversationId("missing-session")).toThrow(
      /Chat session not found/,
    );
  });

  it("preserves a new chat when another tab saves its cached history", async () => {
    const firstTab = await loadStore();
    const older = firstTab.createSession();
    jest.resetModules();
    const secondTab = await loadStore();
    secondTab.getAllSessions();

    const newer = firstTab.createSession();
    const conversationId = firstTab.ensureConversationId(newer.id);
    secondTab.updateSessionTitle(older.id, "Updated in another tab");

    jest.resetModules();
    const reloaded = await loadStore();
    expect(reloaded.getSession(newer.id)?.conversationId).toBe(conversationId);
    expect(reloaded.getSession(older.id)?.title).toBe("Updated in another tab");
  });

  it("merges a buffered message save with a chat created in another tab", async () => {
    jest.useFakeTimers();
    try {
      const firstTab = await loadStore();
      const older = firstTab.createSession();
      jest.resetModules();
      const secondTab = await loadStore();
      secondTab.getAllSessions();
      firstTab.updateSessionMessages(older.id, [
        { id: "message-1", role: "user", content: "Keep my requirements" },
      ]);
      const newer = secondTab.createSession();
      jest.advanceTimersByTime(500);

      jest.resetModules();
      const reloaded = await loadStore();
      expect(reloaded.getSession(newer.id)).not.toBeNull();
      expect(reloaded.getSession(older.id)?.messages[0].content).toBe(
        "Keep my requirements",
      );
    } finally {
      jest.useRealTimers();
    }
  });

  it("does not restore a deleted chat when a stale tab saves another chat", async () => {
    const firstTab = await loadStore();
    const deleted = firstTab.createSession();
    const retained = firstTab.createSession();
    jest.resetModules();
    const secondTab = await loadStore();
    secondTab.getAllSessions();
    const newer = firstTab.createSession();
    secondTab.deleteSession(deleted.id);
    firstTab.updateSessionTitle(retained.id, "Retained chat");

    jest.resetModules();
    const reloaded = await loadStore();
    expect(reloaded.getSession(deleted.id)).toBeNull();
    expect(reloaded.getSession(newer.id)).not.toBeNull();
    expect(reloaded.getSession(retained.id)?.title).toBe("Retained chat");
  });

  it("reloads history after another tab changes storage", async () => {
    const firstTab = await loadStore();
    firstTab.getAllSessions();
    jest.resetModules();
    const secondTab = await loadStore();
    const newer = secondTab.createSession();
    window.dispatchEvent(
      new StorageEvent("storage", { key: "cip_ai_chat_sessions" }),
    );
    expect(firstTab.getSession(newer.id)).not.toBeNull();
  });

  it.each([false, true])(
    "does not overwrite another tab's conversation identity or deletion (deleted=%s)",
    async (deleted) => {
      jest.useFakeTimers();
      try {
        const firstTab = await loadStore();
        const session = firstTab.createSession();
        jest.resetModules();
        const secondTab = await loadStore();
        secondTab.getAllSessions();
        firstTab.updateSessionMessages(session.id, [
          { id: "message-1", role: "user", content: "Keep my requirements" },
        ]);
        const conversationId = secondTab.ensureConversationId(session.id);
        if (deleted) {
          secondTab.deleteSession(session.id);
        }
        jest.advanceTimersByTime(500);

        jest.resetModules();
        const reloaded = await loadStore();
        if (deleted) {
          expect(reloaded.getSession(session.id)).toBeNull();
        } else {
          expect(reloaded.getSession(session.id)?.conversationId).toBe(
            conversationId,
          );
          expect(reloaded.getSession(session.id)?.messages[0].content).toBe(
            "Keep my requirements",
          );
        }
      } finally {
        jest.useRealTimers();
      }
    },
  );

  it("uses the persisted conversation identity before a buffered save completes", async () => {
    jest.useFakeTimers();
    try {
      const firstTab = await loadStore();
      const session = firstTab.createSession();
      jest.resetModules();
      const secondTab = await loadStore();
      secondTab.getAllSessions();
      firstTab.updateSessionMessages(session.id, [
        { id: "message-1", role: "user", content: "Keep my requirements" },
      ]);
      const conversationId = secondTab.ensureConversationId(session.id);
      expect(firstTab.ensureConversationId(session.id)).toBe(conversationId);
      secondTab.deleteSession(session.id);
      expect(() => firstTab.ensureConversationId(session.id)).toThrow(
        "Chat session not found",
      );
      jest.advanceTimersByTime(500);
    } finally {
      jest.useRealTimers();
    }
  });
});
