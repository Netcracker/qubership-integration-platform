/** @jest-environment jsdom */
import { act, renderHook, waitFor } from "@testing-library/react";
import type {
  Event,
  PermissionRequest,
  QuestionRequest,
  Session,
} from "@opencode-ai/sdk/v2/client";
import { useOpenCodeChat } from "../../src/ai/opencode/useOpenCodeChat.ts";
import { createChatClient } from "../../src/ai/opencode/client.ts";
import { prepareFiles } from "../../src/ai/opencode/files.ts";

jest.mock("../../src/ai/opencode/client.ts", () => ({
  createChatClient: jest.fn(),
  chatError: jest.requireActual<
    typeof import("../../src/ai/opencode/client.ts")
  >("../../src/ai/opencode/client.ts").chatError,
}));
jest.mock("../../src/ai/opencode/files.ts", () => ({
  prepareFiles: jest.fn(),
}));

const session: Session = {
  id: "s",
  slug: "s",
  title: "Test",
  projectID: "p",
  directory: "/test",
  version: "1.19.0",
  time: { created: 1, updated: 1 },
};
const permission: PermissionRequest = {
  id: "permission",
  sessionID: "s",
  permission: "bash",
  patterns: ["echo test"],
  always: [],
  metadata: {},
};
const question: QuestionRequest = {
  id: "question",
  sessionID: "s",
  questions: [
    {
      header: "Test",
      question: "Choose",
      options: [{ label: "Yes", description: "Continue" }],
    },
  ],
};

function mockClient() {
  let wake: ((event: Event | null) => void) | undefined;
  let options: { signal: AbortSignal } | undefined;
  const client = {
    global: {
      health: jest.fn().mockResolvedValue({ data: { version: "1.19.0" } }),
    },
    session: {
      list: jest.fn().mockResolvedValue({ data: [session] }),
      create: jest.fn().mockResolvedValue({ data: session }),
      messages: jest.fn().mockResolvedValue({ data: [] }),
      status: jest.fn().mockResolvedValue({ data: { s: { type: "idle" } } }),
      promptAsync: jest.fn().mockResolvedValue({}),
      abort: jest.fn().mockResolvedValue({ data: true }),
      delete: jest.fn().mockResolvedValue({ data: true }),
    },
    permission: {
      list: jest.fn().mockResolvedValue({ data: [permission] }),
      reply: jest.fn().mockResolvedValue({ data: true }),
    },
    question: {
      list: jest.fn().mockResolvedValue({ data: [question] }),
      reply: jest.fn().mockResolvedValue({ data: true }),
      reject: jest.fn().mockResolvedValue({ data: true }),
    },
    event: {
      subscribe: jest
        .fn()
        .mockImplementation((_params, opts: { signal: AbortSignal }) => {
          options = opts;
          return Promise.resolve({
            stream: (async function* () {
              yield {
                id: "connected",
                type: "server.connected",
                properties: {},
              };
              while (!opts.signal.aborted) {
                const event = await new Promise<Event | null>((resolve) => {
                  wake = resolve;
                  opts.signal.addEventListener("abort", () => resolve(null), {
                    once: true,
                  });
                });
                if (!event) return;
                yield event;
              }
            })(),
          });
        }),
    },
  };
  jest.mocked(createChatClient).mockImplementation((url) => ({
    url,
    client: client as unknown as ReturnType<typeof createChatClient>["client"],
  }));
  return {
    client,
    emit: (event: Event) => wake?.(event),
    signal: () => options?.signal,
  };
}

beforeEach(() => {
  if (!AbortSignal.timeout)
    Object.defineProperty(AbortSignal, "timeout", {
      value: () => new AbortController().signal,
    });
  localStorage.clear();
  jest.mocked(prepareFiles).mockResolvedValue([]);
});

it("should avoid appending queued deltas to a restored snapshot", async () => {
  const { client, emit } = mockClient();
  const part = {
    id: "p",
    type: "text" as const,
    sessionID: "s",
    messageID: "m",
    text: "Hello world",
  };
  client.session.messages.mockResolvedValue({
    data: [{ info: { id: "m" }, parts: [part] }],
  });
  const { result } = renderHook(() => useOpenCodeChat(true));
  await act(() =>
    result.current.connect("http://localhost:4096", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  await act(async () =>
    emit({
      id: "queued",
      type: "message.part.delta",
      properties: {
        sessionID: "s",
        messageID: "m",
        partID: "p",
        field: "text",
        delta: " world",
      },
    }),
  );
  expect(result.current.messages[0].parts[0]).toMatchObject({
    text: "Hello world",
  });
  await act(async () =>
    emit({
      id: "snapshot",
      type: "message.part.updated",
      properties: {
        sessionID: "s",
        part: { ...part, text: "Hello world again" },
        time: 1,
      },
    }),
  );
  expect(result.current.messages[0].parts[0]).toMatchObject({
    text: "Hello world again",
  });
});

it("should retain a turn error when the server returns to idle", async () => {
  const { emit } = mockClient();
  const { result } = renderHook(() => useOpenCodeChat(true));
  await act(() =>
    result.current.connect("http://localhost:4096", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  await act(async () =>
    emit({
      id: "error",
      type: "session.error",
      properties: {
        sessionID: "s",
        error: { name: "UnknownError", data: { message: "Turn failed" } },
      },
    }),
  );
  await act(async () =>
    emit({ id: "idle", type: "session.idle", properties: { sessionID: "s" } }),
  );
  expect(result.current.error).toBe("Turn failed");
  expect(result.current.busy).toBe(false);
});

it("should restore pending cards, isolate sessions, and close the stream with the panel", async () => {
  const { client, emit, signal } = mockClient();
  localStorage.setItem("qip_opencode_session:http://localhost:4096", "s");
  const { result, rerender } = renderHook(({ open }) => useOpenCodeChat(open), {
    initialProps: { open: true },
  });
  expect(client.global.health).not.toHaveBeenCalled();
  await act(() =>
    result.current.connect("http://localhost:4096", "opencode", ""),
  );
  expect(result.current.error).toBeNull();
  await waitFor(() => expect(result.current.ready).toBe(true));
  expect(result.current.permissions).toEqual([permission]);
  expect(result.current.questions).toEqual([question]);
  expect(client.session.create).not.toHaveBeenCalled();
  await act(async () =>
    emit({
      id: "foreign",
      type: "permission.asked",
      properties: { ...permission, id: "foreign", sessionID: "other" },
    }),
  );
  expect(result.current.permissions).toEqual([permission]);
  const streamSignal = signal();
  rerender({ open: false });
  expect(streamSignal?.aborted).toBe(true);
});

it("should keep a card pending until the server confirms its answer", async () => {
  const { client } = mockClient();
  let finish!: () => void;
  client.permission.reply.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = () => resolve({ data: true });
      }),
  );
  const { result } = renderHook(() => useOpenCodeChat(true));
  await act(() =>
    result.current.connect("http://localhost:4096", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  let reply!: Promise<void>;
  act(() => {
    reply = result.current.replyPermission("permission", "once");
  });
  expect(result.current.permissions).toHaveLength(1);
  await act(async () => {
    finish();
    await reply;
  });
  expect(result.current.permissions).toHaveLength(0);
  client.question.reply.mockRejectedValue(
    new Error("Server rejected the reply"),
  );
  await expect(
    result.current.replyQuestion("question", [["Yes"]]),
  ).rejects.toThrow("Server rejected");
  expect(result.current.questions).toHaveLength(1);
});

it("should stop attachment preparation before it can send a prompt", async () => {
  const { client } = mockClient();
  let finish!: () => void;
  jest.mocked(prepareFiles).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = () => resolve([]);
      }),
  );
  const { result } = renderHook(() => useOpenCodeChat(true));
  await act(() =>
    result.current.connect("http://localhost:4096", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  let sent!: Promise<boolean>;
  act(() => {
    sent = result.current.send("Test", []);
  });
  await act(() => result.current.abort());
  await act(async () => {
    finish();
    expect(await sent).toBe(false);
  });
  expect(client.session.promptAsync).not.toHaveBeenCalled();
  expect(client.session.abort).toHaveBeenCalledWith({ sessionID: "s" });
});

it("should keep an accepted turn busy until the server reports idle", async () => {
  const { client, emit } = mockClient();
  const { result } = renderHook(() => useOpenCodeChat(true));
  await act(() =>
    result.current.connect("http://localhost:4096", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  await act(async () =>
    expect(await result.current.send("First", [])).toBe(true),
  );
  expect(result.current.busy).toBe(true);
  await act(async () =>
    expect(await result.current.send("Second", [])).toBe(false),
  );
  expect(client.session.promptAsync).toHaveBeenCalledTimes(1);
  await act(async () =>
    emit({ id: "idle", type: "session.idle", properties: { sessionID: "s" } }),
  );
  expect(result.current.busy).toBe(false);
});

it("should retain send failures and scope the remembered session to its server", async () => {
  const { client } = mockClient();
  localStorage.setItem("qip_ai_chat_sessions", "Rocky history");
  localStorage.setItem("qip_opencode_session:https://first.example", "s");
  const { result } = renderHook(() => useOpenCodeChat(true));
  await act(() =>
    result.current.connect("https://second.example", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  expect(client.session.create).toHaveBeenCalledTimes(1);
  client.session.promptAsync.mockRejectedValue(new Error("Request failed"));
  await act(async () =>
    expect(await result.current.send("Keep this draft", [])).toBe(false),
  );
  expect(result.current.error).toBe("Request failed");
  expect(result.current.busy).toBe(false);
  expect(localStorage.getItem("qip_ai_chat_sessions")).toBe("Rocky history");
  expect(
    localStorage.getItem("qip_opencode_session:https://first.example"),
  ).toBe("s");
});

it("should ignore an earlier connection that completes after switching servers", async () => {
  const { client } = mockClient();
  let finish!: () => void;
  client.global.health.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = () => resolve({ data: { version: "1.19.0" } });
      }),
  );
  const { result } = renderHook(() => useOpenCodeChat(true));
  let earlier!: Promise<void>;
  act(() => {
    earlier = result.current.connect("https://first.example", "opencode", "");
  });
  await act(() =>
    result.current.connect("https://second.example", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  await act(async () => {
    finish();
    await earlier;
  });
  expect(result.current.serverUrl).toBe("https://second.example");
  expect(client.session.create).toHaveBeenCalledTimes(1);
  expect(result.current.ready).toBe(true);
});

it("should stay idle when a turn finishes before the send response arrives", async () => {
  const { client, emit } = mockClient();
  let finish!: () => void;
  client.session.promptAsync.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = () => resolve({});
      }),
  );
  const { result } = renderHook(() => useOpenCodeChat(true));
  await act(() =>
    result.current.connect("http://localhost:4096", "opencode", ""),
  );
  await waitFor(() => expect(result.current.ready).toBe(true));
  let sent!: Promise<boolean>;
  await act(async () => {
    sent = result.current.send("Test", []);
  });
  await act(async () =>
    emit({ id: "idle", type: "session.idle", properties: { sessionID: "s" } }),
  );
  await act(async () => {
    finish();
    expect(await sent).toBe(true);
  });
  expect(result.current.busy).toBe(false);
});
