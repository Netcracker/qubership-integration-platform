import {
  ChatSession,
  ChatMode,
  ChainCreationPlan,
  ElementCreationStatus,
} from "./types.ts";
import { ChatMessage } from "../modelProviders/types.ts";
import { v4 as uuidv4 } from "uuid";

const STORAGE_KEY = "qip_ai_chat_sessions";
const LAST_ACTIVE_SESSION_ID_KEY = "qip_ai_last_active_session_id";
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

  private ensureLoadedSessions(): ChatSession[] {
    if (this.memorySessions) {
      return this.memorySessions;
    }

    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      this.memorySessions = stored ? (JSON.parse(stored) as ChatSession[]) : [];
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
  private saveSessions(sessions: ChatSession[], immediate = false): void {
    this.memorySessions = sessions;
    this.pendingSave = sessions;

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
        localStorage.setItem(STORAGE_KEY, JSON.stringify(this.pendingSave));
      } catch {
        // Ignore storage errors
      }
      this.pendingSave = null;
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
    this.saveSessions(sessions, true); // Immediate save for session creation
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
      this.saveSessions(sessions); // Debounced save for message updates
    }
  }

  updateSessionTitle(sessionId: string, title: string): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.title = title;
      session.updatedAt = Date.now();
      this.saveSessions(sessions, true); // Immediate save for title changes
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
      this.saveSessions(sessions, true);
    }
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
      this.saveSessions(sessions, true);
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
      this.saveSessions(sessions, true);
    }
  }

  updateSessionMode(sessionId: string, mode: ChatMode): void {
    const sessions = this.getStoredSessions();
    const session = sessions.find((s) => s.id === sessionId);
    if (session) {
      session.mode = mode;
      session.updatedAt = Date.now();
      this.saveSessions(sessions, true); // Immediate save for mode changes
    }
  }

  deleteSession(sessionId: string): void {
    this.flushSave(); // Flush any pending saves before deletion
    const sessions = this.getStoredSessions();
    const filtered = sessions.filter((s) => s.id !== sessionId);
    this.saveSessions(filtered, true); // Immediate save for deletion
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
      this.saveSessions(sessions, true); // Immediate save for plan changes
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
        this.saveSessions(sessions);
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
        this.saveSessions(sessions);
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
