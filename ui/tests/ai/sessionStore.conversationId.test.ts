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
    expect(remounted.getSession(sessionId)?.conversationId).toBe(conversationId);
    expect(remounted.ensureConversationId(sessionId)).toBe(conversationId);
  });

  it("should throw when session id is unknown", async () => {
    const store = await loadStore();
    expect(() => store.ensureConversationId("missing-session")).toThrow(
      /Chat session not found/,
    );
  });
});
