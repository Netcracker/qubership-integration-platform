import { useEffect, useRef, useState } from "react";
import type {
  PermissionRequest,
  QuestionRequest,
  Session,
} from "@opencode-ai/sdk/v2/client";
import { chatError, createChatClient } from "./client.ts";
import { prepareFiles } from "./files.ts";
import { type OpenCodeMessage, updateMessages } from "./messages.ts";

const selectionKey = (url: string) => `qip_opencode_session:${url}`;
function rememberSession(url: string, id: string | null) {
  try {
    if (id) localStorage.setItem(selectionKey(url), id);
    else localStorage.removeItem(selectionKey(url));
  } catch {
    /* Session history remains on the server when storage is unavailable. */
  }
}

export function useOpenCodeChat(open: boolean) {
  const [connection, setConnection] = useState<ReturnType<
    typeof createChatClient
  > | null>(null);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [sessionID, setSessionID] = useState<string | null>(null);
  const [messages, setMessages] = useState<OpenCodeMessage[]>([]);
  const [permissions, setPermissions] = useState<PermissionRequest[]>([]);
  const [questions, setQuestions] = useState<QuestionRequest[]>([]);
  const [busy, setBusy] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [ready, setReady] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const sending = useRef(false);

  useEffect(
    () => () => {
      ++generation.current;
    },
    [],
  );

  async function connect(url: string, username: string, password: string) {
    const current = ++generation.current;
    setConnecting(true);
    setReady(false);
    setConnection(null);
    setSessionID(null);
    setMessages([]);
    setSessions([]);
    setPermissions([]);
    setQuestions([]);
    setBusy(false);
    setSubmitting(false);
    setError(null);
    try {
      const next = createChatClient(url, username, password);
      const { data: health } = await next.client.global.health({
        signal: AbortSignal.timeout(10000),
      });
      if (health?.version !== "1.19.0")
        throw new Error(
          `OpenCode 1.19.0 is required. The server reports ${health?.version ?? "an unknown version"}.`,
        );
      const { data: list = [] } = await next.client.session.list(
        {},
        { signal: AbortSignal.timeout(10000) },
      );
      if (current !== generation.current) return;
      let saved: string | null = null;
      try {
        saved = localStorage.getItem(selectionKey(next.url));
      } catch {
        /* Storage is optional. */
      }
      let selected = list.find((s) => s.id === saved);
      if (!selected) {
        selected = (await next.client.session.create({})).data;
        if (current !== generation.current) return;
        if (selected) list.unshift(selected);
      }
      setSessions(list.filter((s) => !s.parentID));
      setSessionID(selected?.id ?? null);
      rememberSession(next.url, selected?.id ?? null);
      setConnection(next);
    } catch (err) {
      if (current === generation.current) setError(chatError(err));
    } finally {
      if (current === generation.current) setConnecting(false);
    }
  }

  useEffect(() => {
    if (!open || !connection) return;
    const controller = new AbortController();
    const { client } = connection;
    const options = { signal: controller.signal };
    let restoredParts = new Set<string>();
    setReady(false);
    async function refresh(resetError = false) {
      const [history, status, pendingPermissions, pendingQuestions] =
        await Promise.all([
          sessionID
            ? client.session.messages({ sessionID }, options)
            : Promise.resolve({ data: [] }),
          client.session.status({}, options),
          client.permission.list({}, options),
          client.question.list({}, options),
        ]);
      if (controller.signal.aborted) return;
      restoredParts = new Set(
        (history.data ?? []).flatMap((m) => m.parts.map((p) => p.id)),
      );
      setMessages((history.data ?? []).map((m) => ({ ...m, id: m.info.id })));
      setBusy(
        Boolean(
          sessionID &&
            status.data?.[sessionID]?.type !== undefined &&
            status.data[sessionID].type !== "idle",
        ),
      );
      setPermissions(
        (pendingPermissions.data ?? []).filter(
          (p) => p.sessionID === sessionID,
        ),
      );
      setQuestions(
        (pendingQuestions.data ?? []).filter((q) => q.sessionID === sessionID),
      );
      setReady(true);
      if (resetError) setError(null);
    }
    async function subscribe() {
      const seen = new Set<string>();
      try {
        const events = await client.event.subscribe(
          {},
          {
            ...options,
            sseMaxRetryAttempts: 1,
            onSseError: (err) => {
              if (!controller.signal.aborted) {
                setReady(false);
                setError(chatError(err));
              }
            },
          },
        );
        for await (const event of events.stream) {
          if (controller.signal.aborted) return;
          if (event.type === "server.connected") {
            await refresh(true);
            continue;
          }
          if (event.id && seen.has(event.id)) continue;
          if (event.id) seen.add(event.id);
          if (
            event.type === "session.created" ||
            event.type === "session.updated"
          ) {
            const info = event.properties.info;
            if (!info.parentID)
              setSessions((list) =>
                list.some((s) => s.id === info.id)
                  ? list.map((s) => (s.id === info.id ? info : s))
                  : [info, ...list],
              );
          }
          if (
            !sessionID ||
            !("sessionID" in event.properties) ||
            event.properties.sessionID !== sessionID
          )
            continue;
          // shortcut: Restored text waits for a snapshot, use replay cursors when enabling streamed recovery.
          if (
            event.type === "message.part.delta" &&
            restoredParts.has(event.properties.partID)
          )
            continue;
          if (event.type === "message.part.updated")
            restoredParts.delete(event.properties.part.id);
          setMessages((list) => updateMessages(list, event, sessionID));
          switch (event.type) {
            case "session.status":
              setBusy(event.properties.status.type !== "idle");
              break;
            case "session.idle":
              await refresh();
              break;
            case "session.error":
              setError(chatError(event.properties.error));
              setBusy(false);
              break;
            case "permission.asked":
              setPermissions((list) => [
                ...list.filter((p) => p.id !== event.properties.id),
                event.properties,
              ]);
              break;
            case "permission.replied":
              setPermissions((list) =>
                list.filter((p) => p.id !== event.properties.requestID),
              );
              break;
            case "question.asked":
              setQuestions((list) => [
                ...list.filter((q) => q.id !== event.properties.id),
                event.properties,
              ]);
              break;
            case "question.replied":
            case "question.rejected":
              setQuestions((list) =>
                list.filter((q) => q.id !== event.properties.requestID),
              );
              break;
          }
        }
        if (!controller.signal.aborted) {
          setReady(false);
          setError(
            "OpenCode disconnected. Reconnect to restore the conversation.",
          );
        }
      } catch (err) {
        if (!controller.signal.aborted) {
          setReady(false);
          setError(chatError(err));
        }
      }
    }
    void subscribe();
    return () => controller.abort();
  }, [open, connection, sessionID]);

  function selectSession(id: string | null) {
    ++generation.current;
    setReady(false);
    setSessionID(id);
    setMessages([]);
    setPermissions([]);
    setQuestions([]);
    setBusy(false);
    setSubmitting(false);
    setError(null);
    if (connection) rememberSession(connection.url, id);
  }

  async function newSession() {
    if (!connection) return;
    const current = generation.current;
    try {
      const { data } = await connection.client.session.create({});
      if (!data || current !== generation.current) return;
      setSessions((list) => [data, ...list.filter((s) => s.id !== data.id)]);
      selectSession(data.id);
    } catch (err) {
      if (current === generation.current) setError(chatError(err));
    }
  }

  async function send(text: string, files: File[]): Promise<boolean> {
    if (
      !connection ||
      !sessionID ||
      !ready ||
      busy ||
      sending.current ||
      (!text.trim() && !files.length)
    )
      return false;
    sending.current = true;
    const current = generation.current;
    setSubmitting(true);
    setError(null);
    try {
      const attachments = await prepareFiles(files);
      if (current !== generation.current) return false;
      setBusy(true);
      await connection.client.session.promptAsync({
        sessionID,
        parts: [
          ...(text.trim() ? [{ type: "text" as const, text }] : []),
          ...attachments,
        ],
      });
      return current === generation.current;
    } catch (err) {
      if (current === generation.current) {
        setError(chatError(err));
        setBusy(false);
      }
      return false;
    } finally {
      sending.current = false;
      setSubmitting(false);
    }
  }

  async function abort() {
    if (!connection || !sessionID) return;
    const current = ++generation.current;
    setSubmitting(false);
    try {
      await connection.client.session.abort({ sessionID });
      if (current === generation.current) setBusy(false);
    } catch (err) {
      if (current === generation.current) setError(chatError(err));
    }
  }

  async function clear() {
    if (!connection || !sessionID) return;
    const current = generation.current;
    try {
      await connection.client.session.delete({ sessionID });
      if (current !== generation.current) return;
      setSessions((list) => list.filter((s) => s.id !== sessionID));
      selectSession(null);
      await newSession();
    } catch (err) {
      if (current === generation.current) setError(chatError(err));
    }
  }

  async function replyPermission(
    requestID: string,
    reply: "once" | "always" | "reject",
  ) {
    if (!connection)
      throw new Error("Reconnect before answering this request.");
    await connection.client.permission.reply({ requestID, reply });
    setPermissions((list) => list.filter((p) => p.id !== requestID));
  }
  async function replyQuestion(requestID: string, answers: string[][] | null) {
    if (!connection)
      throw new Error("Reconnect before answering this request.");
    if (answers) await connection.client.question.reply({ requestID, answers });
    else await connection.client.question.reject({ requestID });
    setQuestions((list) => list.filter((q) => q.id !== requestID));
  }

  return {
    connect,
    sessions,
    sessionID,
    messages,
    permissions,
    questions,
    busy: busy || submitting,
    ready,
    connecting,
    error,
    connected: Boolean(connection),
    serverUrl: connection?.url,
    selectSession,
    newSession,
    send,
    abort,
    clear,
    replyPermission,
    replyQuestion,
  };
}
