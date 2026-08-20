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
import type { ChatMessage } from "../../src/ai/modelProviders/types.ts";

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
