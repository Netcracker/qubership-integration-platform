/**
 * @jest-environment jsdom
 */
import { beforeEach, describe, expect, it } from "@jest/globals";

describe("ChatSessionStore conversationId (A1)", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.resetModules();
  });

  async function loadStore() {
    const mod = await import("../../src/ai/sessions/sessionStore.ts");
    return mod.getChatSessionStore();
  }

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
      new StorageEvent("storage", { key: "qip_ai_chat_sessions" }),
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
