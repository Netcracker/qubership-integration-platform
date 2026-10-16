import {
  ChatSession,
  ChatMode,
  ChainCreationPlan,
  ElementCreationStatus,
} from "./types.ts";
import { ChatMessage } from "../modelProviders/types.ts";
import { v4 as uuidv4 } from "uuid";

const STORAGE_KEY = "cip_ai_chat_sessions";
const LAST_ACTIVE_SESSION_ID_KEY = "cip_ai_last_active_session_id";
const LEGACY_STORAGE_KEY = "qip_ai_chat_sessions";
const LEGACY_LAST_ACTIVE_SESSION_ID_KEY = "qip_ai_last_active_session_id";
const STORAGE_DEBOUNCE_MS = 500;

function getLatestSessionId(sessions: ChatSession[]): string | null {
  if (sessions.length === 0) {
    return null;
  }
  let latest = sessions[0];
  for (let i = 1; i < sessions.length; i++) {
    if (sessions[i].updatedAt > latest.updatedAt) {
      latest = sessions[i];
    }
  }
  return latest.id;
}

class ChatSessionStore {
  private memorySessions: ChatSession[] | null = null;
  private saveTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private pendingSave: ChatSession[] | null = null;
  private changedSessionFields = new Map<
    string,
    Set<keyof ChatSession> | null
  >();

  constructor() {
    this.migrateLegacySessions();
    window.addEventListener("storage", (event) => {
      if (event.key === STORAGE_KEY && this.pendingSave === null) {
        this.memorySessions = null;
      }
    });
  }

  private migrateLegacySessions(): void {
    try {
      const legacy = localStorage.getItem(LEGACY_STORAGE_KEY);
      if (legacy === null) return;
      const sessions = new Map(
        (JSON.parse(legacy) as ChatSession[]).map((session) => [
          session.id,
          session,
        ]),
      );
      for (const session of this.readPersistedSessions()) {
        sessions.set(session.id, session);
      }
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify(Array.from(sessions.values())),
      );
      const activeSession = localStorage.getItem(
        LEGACY_LAST_ACTIVE_SESSION_ID_KEY,
      );
      if (activeSession && !localStorage.getItem(LAST_ACTIVE_SESSION_ID_KEY)) {
        localStorage.setItem(LAST_ACTIVE_SESSION_ID_KEY, activeSession);
      }
      localStorage.removeItem(LEGACY_STORAGE_KEY);
      localStorage.removeItem(LEGACY_LAST_ACTIVE_SESSION_ID_KEY);
    } catch {
      // Keep legacy history if the migration cannot be saved.
    }
  }

  private readPersistedSessions(): ChatSession[] {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? (JSON.parse(stored) as ChatSession[]) : [];
  }

  private ensureLoadedSessions(): ChatSession[] {
    if (this.memorySessions) {
      return this.memorySessions;
    }

    try {
      this.memorySessions = this.readPersistedSessions();
    } catch {
      this.memorySessions = [];
    }

    return this.memorySessions;
  }

  private getStoredSessions(): ChatSession[] {
    return this.ensureLoadedSessions();
  }

  /**
   * Debounced save to localStorage - prevents blocking main thread during streaming
   */
  private saveSessions(
    sessions: ChatSession[],
    sessionId: string,
    fields: (keyof ChatSession)[] | null,
    immediate = false,
  ): void {
    this.memorySessions = sessions;
    this.pendingSave = sessions;
    if (fields === null) {
      this.changedSessionFields.set(sessionId, null);
    } else if (this.changedSessionFields.get(sessionId) !== null) {
      const changed =
        this.changedSessionFields.get(sessionId) ??
        new Set<keyof ChatSession>();
      fields.forEach((field) => changed.add(field));
      this.changedSessionFields.set(sessionId, changed);
    }

    if (immediate) {
      // Immediate save (for session creation/deletion)
      this.flushSave();
      return;
    }

    // Debounced save for message updates
    if (this.saveTimeoutId === null) {
      this.saveTimeoutId = setTimeout(() => {
        this.flushSave();
      }, STORAGE_DEBOUNCE_MS);
    }
  }

  private flushSave(): void {
    if (this.saveTimeoutId !== null) {
      clearTimeout(this.saveTimeoutId);
      this.saveTimeoutId = null;
    }
    if (this.pendingSave !== null) {
      try {
        // ponytail: concurrent writes to the same field use the last writer.
        const merged = new Map(
          this.readPersistedSessions().map((session) => [session.id, session]),
        );
        for (const [sessionId, fields] of this.changedSessionFields) {
          const session = this.pendingSave.find(
            (item) => item.id === sessionId,
          );
          const persisted = merged.get(sessionId);
          if (!session) {
            merged.delete(sessionId);
          } else if (fields === null) {
            merged.set(sessionId, session);
          } else if (persisted) {
            const patch = Object.fromEntries(
              Array.from(fields, (field) => [field, session[field]]),
            );
            merged.set(sessionId, { ...persisted, ...patch });
          }
        }
        const sessions = Array.from(merged.values());
        localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
        this.memorySessions = sessions;
      } catch {
        // Ignore storage errors
      }
      this.pendingSave = null;
      this.changedSessionFields.clear();
    }
  }

  /**
   * Ensures message has a unique ID
   */
  private ensureMessageId(message: ChatMessage): ChatMessage {
    if (!message.id) {
      return { ...message, id: uuidv4() };
    }
    return message;
  }

  getAllSessions(): ChatSession[] {
    return this.getStoredSessions();
  }

  setLastActiveSessionId(sessionId: string): void {
    try {
      localStorage.setItem(LAST_ACTIVE_SESSION_ID_KEY, sessionId);
    } catch {
      // Ignore storage errors
    }
  }

  /** Last opened tab, or the most recently updated chat if none is stored. */
  resolveDefaultSessionId(sessions: ChatSession[]): string | null {
    if (sessions.length === 0) {
      return null;
    }
    let sessionId: string | null = null;
    try {
      const stored = localStorage.getItem(LAST_ACTIVE_SESSION_ID_KEY);
      if (stored && sessions.some((s) => s.id === stored)) {
        sessionId = stored;
      }
    } catch {
      // Ignore storage errors
    }
    if (!sessionId) {
      sessionId = getLatestSessionId(sessions);
    }
    if (sessionId) {
      this.setLastActiveSessionId(sessionId);
    }
    return sessionId;
  }

  getSession(sessionId: string): ChatSession | null {
    const sessions = this.getStoredSessions();
    return sessions.find((s) => s.id === sessionId) || null;
  }

  createSession(mode: ChatMode = "ask"): ChatSession {
    const sessions = this.getStoredSessions();
    const now = Date.now();
    const newSession: ChatSession = {
      id: uuidv4(),
      title: "New Chat",
      messages: [],
      mode,
      createdAt: now,
      updatedAt: now,
    };
    sessions.push(newSession);
    this.saveSessions(sessions, newSession.id, null, true); // Immediate save for session creation
    this.setLastActiveSessionId(newSession.id);
    return newSession;
  }

  /**
   * Updates session messages with deduplication and ID generation
   */
  updateSessionMessages(sessionId: string, messages: ChatMessage[]): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      // Ensure all messages have IDs and deduplicate
      const messagesWithIds = messages.map((m) => this.ensureMessageId(m));

      // Deduplicate by ID (keep last occurrence)
      const seen = new Map<string, ChatMessage>();
      for (const msg of messagesWithIds) {
        if (msg.id) {
          seen.set(msg.id, msg);
        }
      }

      session.messages = Array.from(seen.values());
      session.updatedAt = Date.now();
      this.saveSessions(sessions, sessionId, ["messages", "updatedAt"]); // Debounced save for message updates
    }
  }

  updateSessionTitle(sessionId: string, title: string): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.title = title;
      session.updatedAt = Date.now();
      this.saveSessions(sessions, sessionId, ["title", "updatedAt"], true); // Immediate save for title changes
    }
  }

  /**
   * Update (or clear) the server-side conversation ID for lightweight chat mode.
   */
  updateConversationId(
    sessionId: string,
    conversationId: string | undefined,
  ): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.conversationId = conversationId;
      session.updatedAt = Date.now();
      this.saveSessions(
        sessions,
        sessionId,
        ["conversationId", "updatedAt"],
        true,
      );
    }
  }

  /**
   * Return this session's conversationId, minting and persisting one immediately if missing.
   * Call before the first SSE so a failed or aborted stream does not leave the session
   * without an id (which would re-mint on the next send).
   */
  ensureConversationId(sessionId: string): string {
    const session = this.getSession(sessionId);
    const persisted = this.readPersistedSessions().find(
      (item) => item.id === sessionId,
    );
    if (!session || !persisted) {
      throw new Error(`Chat session not found: ${sessionId}`);
    }
    session.conversationId = persisted.conversationId;
    if (session.conversationId) {
      return session.conversationId;
    }
    const conversationId = uuidv4();
    this.updateConversationId(sessionId, conversationId);
    return conversationId;
  }

  updateSessionLastAttachmentUrls(
    sessionId: string,
    urls: string[] | undefined,
  ): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.lastAttachmentUrls = urls;
      session.updatedAt = Date.now();
      this.saveSessions(
        sessions,
        sessionId,
        ["lastAttachmentUrls", "updatedAt"],
        true,
      );
    }
  }

  updateSessionLastAttachmentObjectKeys(
    sessionId: string,
    keys: string[] | undefined,
  ): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.lastAttachmentObjectKeys = keys;
      session.updatedAt = Date.now();
      this.saveSessions(
        sessions,
        sessionId,
        ["lastAttachmentObjectKeys", "updatedAt"],
        true,
      );
    }
  }

  updateSessionMode(sessionId: string, mode: ChatMode): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.mode = mode;
      session.updatedAt = Date.now();
      this.saveSessions(sessions, sessionId, ["mode", "updatedAt"], true); // Immediate save for mode changes
    }
  }

  deleteSession(sessionId: string): void {
    this.flushSave(); // Flush any pending saves before deletion
    const sessions = this.getStoredSessions();
    const filtered = sessions.filter((s) => s.id !== sessionId);
    this.saveSessions(filtered, sessionId, null, true); // Immediate save for deletion
    try {
      if (localStorage.getItem(LAST_ACTIVE_SESSION_ID_KEY) === sessionId) {
        const nextId = getLatestSessionId(filtered);
        if (nextId) {
          localStorage.setItem(LAST_ACTIVE_SESSION_ID_KEY, nextId);
        } else {
          localStorage.removeItem(LAST_ACTIVE_SESSION_ID_KEY);
        }
      }
    } catch {
      // Ignore storage errors
    }
  }

  updateChainCreationPlan(
    sessionId: string,
    plan: ChainCreationPlan | null,
  ): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.chainCreationPlan = plan || undefined;
      session.updatedAt = Date.now();
      this.saveSessions(
        sessions,
        sessionId,
        ["chainCreationPlan", "updatedAt"],
        true,
      ); // Immediate save for plan changes
    }
  }

  getChainCreationPlan(sessionId: string): ChainCreationPlan | null {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    return session?.chainCreationPlan || null;
  }

  updatePlannedElementStatus(
    sessionId: string,
    elementId: string,
    status: ElementCreationStatus,
    createdElementId?: string,
    error?: string,
  ): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session?.chainCreationPlan) {
      const element = session.chainCreationPlan.elements.find(
        (e) => e.id === elementId,
      );
      if (element) {
        element.status = status;
        if (createdElementId) {
          element.elementId = createdElementId;
        }
        if (error) {
          element.error = error;
        }
        if (status === "verified") {
          element.verifiedAt = Date.now();
        }
        session.chainCreationPlan.updatedAt = Date.now();
        session.updatedAt = Date.now();
        this.saveSessions(sessions, sessionId, [
          "chainCreationPlan",
          "updatedAt",
        ]);
      }
    }
  }

  updatePlannedConnectionStatus(
    sessionId: string,
    connectionId: string,
    status: "created" | "failed",
    error?: string,
  ): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session?.chainCreationPlan) {
      const connection = session.chainCreationPlan.connections.find(
        (c) => c.id === connectionId,
      );
      if (connection) {
        connection.status = status;
        if (error) {
          connection.error = error;
        }
        session.chainCreationPlan.updatedAt = Date.now();
        session.updatedAt = Date.now();
        this.saveSessions(sessions, sessionId, [
          "chainCreationPlan",
          "updatedAt",
        ]);
      }
    }
  }
}

let instance: ChatSessionStore | null = null;

export function getChatSessionStore(): ChatSessionStore {
  if (!instance) {
    instance = new ChatSessionStore();
  }
  return instance;
}
