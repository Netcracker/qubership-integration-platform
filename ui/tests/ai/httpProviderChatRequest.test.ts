import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from "@jest/globals";

const mockPost =
  jest.fn<
    (url: string, body: unknown, config?: unknown) => Promise<{ data: unknown }>
  >();

jest.mock("axios", () => ({
  __esModule: true,
  default: {
    post: (...args: unknown[]) =>
      (mockPost as unknown as (...a: unknown[]) => Promise<{ data: unknown }>)(
        ...args,
      ),
  },
  AxiosError: class AxiosError extends Error {},
}));

import { HttpAiModelProvider } from "../../src/ai/modelProviders/httpProvider.ts";
import type {
  ChatMessage,
  StreamingChunk,
} from "../../src/ai/modelProviders/types.ts";

function buildMessages(): ChatMessage[] {
  return [{ role: "user", content: "hello" }];
}

/** Resolves fetch with a body that yields no SSE chunks, so streamChat returns immediately. */
function mockEmptyStreamResponse() {
  const fetchMock = jest.fn<typeof fetch>().mockResolvedValue({
    ok: true,
    body: {
      getReader: () => ({
        read: async () => ({ done: true, value: undefined }),
      }),
    },
    text: async () => "",
  } as unknown as Response);
  global.fetch = fetchMock;
  return fetchMock;
}

describe("HttpAiModelProvider chat() request body", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should carry the decision field when the request answers a decision card", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({
      messages: buildMessages(),
      decision: {
        action: "approve",
        artifactType: "implementation-plan",
        artifactHash: "sha256:abc",
        revision: 3,
        comment: "Looks good",
      },
    });

    expect(mockPost).toHaveBeenCalledTimes(1);
    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    expect(body.decision).toEqual({
      action: "approve",
      artifactType: "implementation-plan",
      artifactHash: "sha256:abc",
      revision: 3,
      comment: "Looks good",
    });
  });

  it("should omit the decision field when the request has no decision", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({ messages: buildMessages() });

    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    // The property may exist with value `undefined`; JSON.stringify is what the
    // wire actually sees, and it drops `undefined` values.
    expect(JSON.stringify(body)).not.toContain("decision");
  });
});

describe("HttpAiModelProvider chat() chain context", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should name the open chain in the attachment when a chain is in context", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({
      messages: buildMessages(),
      context: {
        type: "chain",
        chainId: "chain-42",
        compactSchema: {
          chainId: "chain-42",
          chainName: "Order sync",
          elements: [],
          connections: [],
        },
      },
    });

    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    expect(body.attachment).toContain(
      "## Current Chain: Order sync (ID: chain-42)",
    );
  });

  it("should keep the element dump out of the attachment when a chain is in context", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({
      messages: buildMessages(),
      context: {
        type: "chain",
        chainId: "chain-42",
        compactSchema: {
          chainId: "chain-42",
          chainName: "Order sync",
          elements: [
            { id: "element-script", type: "script", name: "Normalize payload" },
          ],
          connections: [],
        },
      },
    });

    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    expect(body.attachment).not.toContain("Normalize payload");
  });

  it("should send no scenario hint when a chain is open", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({
      messages: buildMessages(),
      context: { type: "chain", chainId: "chain-42" },
    });

    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    // An open chain is a place, not an instruction: the server classifies what was asked.
    expect(body.scenarioHint).toBeNull();
  });

  it("should drop IMPLEMENT_CHAIN when a chain is open", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({
      messages: buildMessages(),
      scenarioHint: "IMPLEMENT_CHAIN",
      context: { type: "chain", chainId: "chain-42" },
    });

    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    expect(body.scenarioHint).toBeNull();
  });

  it("should still send IMPLEMENT_CHAIN when no chain is open", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({
      messages: buildMessages(),
      scenarioHint: "IMPLEMENT_CHAIN",
    });

    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    expect(body.scenarioHint).toBe("IMPLEMENT_CHAIN");
  });

  it("should still send an explicit scenario hint when the caller sets one", async () => {
    mockPost.mockResolvedValue({ data: { messages: [] } });
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.chat({
      messages: buildMessages(),
      scenarioHint: "COMPARE_AND_PATCH",
      context: { type: "chain", chainId: "chain-42" },
    });

    const [, body] = mockPost.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ];
    expect(body.scenarioHint).toBe("COMPARE_AND_PATCH");
  });
});

describe("HttpAiModelProvider streamChat() request body", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("should carry the decision field on the SSE POST body", async () => {
    const fetchMock = mockEmptyStreamResponse();
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.streamChat(
      {
        messages: buildMessages(),
        decision: { action: "request-changes", comment: "" },
      },
      () => {},
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body.decision).toEqual({ action: "request-changes", comment: "" });
  });

  it("should bind Continue repair to the current revision and the same conversation", async () => {
    const fetchMock = mockEmptyStreamResponse();
    const provider = new HttpAiModelProvider("https://ai.example.com");
    await provider.streamChat(
      {
        conversationId: "conversation-recovery-1",
        messages: buildMessages(),
        decision: { action: "continue-repair", revision: 19 },
      },
      () => {},
    );
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body.conversationId).toBe("conversation-recovery-1");
    expect(body.decision).toEqual({ action: "continue-repair", revision: 19 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("should drop IMPLEMENT_CHAIN on the SSE POST when a chain is open", async () => {
    const fetchMock = mockEmptyStreamResponse();
    const provider = new HttpAiModelProvider("https://ai.example.com");

    await provider.streamChat(
      {
        messages: buildMessages(),
        scenarioHint: "IMPLEMENT_CHAIN",
        context: { type: "chain", chainId: "chain-42" },
      },
      () => {},
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body.scenarioHint).toBeNull();
  });
});

describe("HttpAiModelProvider stream start failures", () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("should classify a 403 before the stream as access denied and keep the raw text as detail", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue({
      ok: false,
      status: 403,
      text: async () => "Forbidden by gateway",
    } as unknown as Response);
    const chunks: StreamingChunk[] = [];

    await new HttpAiModelProvider("https://ai.example.com").streamChat(
      { messages: buildMessages() },
      (chunk) => chunks.push(chunk),
    );

    expect(chunks).toEqual([
      {
        type: "error",
        errorMessage: "Forbidden by gateway",
        failureClass: "access-denied",
      },
    ]);
  });

  it("should classify a 401 before the stream as an expired session", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue({
      ok: false,
      status: 401,
      text: async () => "",
    } as unknown as Response);
    const chunks: StreamingChunk[] = [];

    await new HttpAiModelProvider("https://ai.example.com").streamChat(
      { messages: buildMessages() },
      (chunk) => chunks.push(chunk),
    );

    expect(chunks).toEqual([
      {
        type: "error",
        errorMessage: "HTTP 401",
        failureClass: "session-expired",
      },
    ]);
  });

  it("should keep other stream start failures unclassified", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => "Internal error",
    } as unknown as Response);
    const chunks: StreamingChunk[] = [];

    await new HttpAiModelProvider("https://ai.example.com").streamChat(
      { messages: buildMessages() },
      (chunk) => chunks.push(chunk),
    );

    expect(chunks).toEqual([
      { type: "error", errorMessage: "Internal error", transportError: true },
    ]);
  });
});

describe("HttpAiModelProvider token renewal", () => {
  it("should post the current token to the turn when the server reports an expired token", async () => {
    const frames = [
      'event: meta\ndata: {"conversationId":"c-1"}\n\n',
      'event: step\ndata: {"id":"auth-token","kind":"auth","status":"token_expired"}\n\n',
      "event: done\ndata: c-1\n\n",
    ];
    const reads = frames.map((frame) => ({
      done: false,
      value: new TextEncoder().encode(frame),
    }));
    const fetchMock = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce({
        ok: true,
        body: {
          getReader: () => ({
            read: async () => reads.shift() ?? { done: true, value: undefined },
          }),
        },
      } as unknown as Response)
      .mockResolvedValue({ ok: true } as Response);
    global.fetch = fetchMock;
    const chunks: string[] = [];

    await new HttpAiModelProvider("https://ai.example.com").streamChat(
      { messages: buildMessages() },
      (chunk) => chunks.push(chunk.type),
    );

    expect(chunks).toEqual(["meta", "done"]);
    const renewal = fetchMock.mock.calls[1];
    expect(renewal[0]).toBe("https://ai.example.com/api/v1/chat/c-1/token");
    expect((renewal[1] as RequestInit).method).toBe("POST");
  });

  it("should show nothing to the user when the renew request itself is rejected with 401 or 403", async () => {
    for (const status of [401, 403]) {
      const frames = [
        'event: meta\ndata: {"conversationId":"c-1"}\n\n',
        'event: step\ndata: {"id":"auth-token","kind":"auth","status":"token_expired"}\n\n',
        "event: done\ndata: c-1\n\n",
      ];
      const reads = frames.map((frame) => ({
        done: false,
        value: new TextEncoder().encode(frame),
      }));
      const fetchMock = jest
        .fn<typeof fetch>()
        .mockResolvedValueOnce({
          ok: true,
          body: {
            getReader: () => ({
              read: async () =>
                reads.shift() ?? { done: true, value: undefined },
            }),
          },
        } as unknown as Response)
        .mockResolvedValue({ ok: false, status } as Response);
      global.fetch = fetchMock;
      const warn = jest
        .spyOn(console, "warn")
        .mockImplementation(() => undefined);
      const chunks: StreamingChunk[] = [];

      await new HttpAiModelProvider("https://ai.example.com").streamChat(
        { messages: buildMessages() },
        (chunk) => chunks.push(chunk),
      );
      await new Promise((resolve) => setTimeout(resolve, 10));

      expect(chunks.map((chunk) => chunk.type)).toEqual(["meta", "done"]);
      expect(warn).toHaveBeenCalledWith(
        `[AI] Token renewal failed with status ${status}`,
      );
      warn.mockRestore();
    }
  });
});
