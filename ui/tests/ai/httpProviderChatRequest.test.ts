import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";

const mockPost = jest.fn<
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
    const [, body] = mockPost.mock.calls[0] as [string, Record<string, unknown>];
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

    const [, body] = mockPost.mock.calls[0] as [string, Record<string, unknown>];
    // The property may exist with value `undefined`; JSON.stringify is what the
    // wire actually sees, and it drops `undefined` values.
    expect(JSON.stringify(body)).not.toContain("decision");
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
});
