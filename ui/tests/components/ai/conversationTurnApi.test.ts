import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from "@jest/globals";
import {
  resetConversation,
  truncateConversation,
  resolveServerMessageIndexes,
} from "../../../src/components/ai/conversationTurnApi.ts";
import { getHeadersForContext } from "../../../src/api/rest/requestHeadersInterceptor.ts";

jest.mock("../../../src/api/rest/requestHeadersInterceptor.ts", () => ({
  getHeadersForContext: jest.fn(),
}));
const fetchMock = jest.fn<typeof fetch>();
const originalFetch = global.fetch;
beforeEach(() => {
  global.fetch = fetchMock;
  jest
    .mocked(getHeadersForContext)
    .mockReturnValue({ Authorization: "Bearer test-token" });
});
afterEach(() => {
  global.fetch = originalFetch;
});

it("truncates the encoded conversation with its server message index and cancellation signal", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  const controller = new AbortController();
  await truncateConversation(
    "https://ai.example.test/",
    "conversation/one",
    3,
    controller.signal,
  );
  expect(fetchMock).toHaveBeenCalledWith(
    "https://ai.example.test/api/v1/chat/conversations/conversation%2Fone/truncate",
    {
      method: "POST",
      headers: {
        Authorization: "Bearer test-token",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ afterMessageIndex: 3 }),
      signal: controller.signal,
    },
  );
});

it("resets a conversation without a bearer header when none is available", async () => {
  jest.mocked(getHeadersForContext).mockReturnValue({});
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  await resetConversation("https://ai.example.test/", "conversation/one");
  expect(fetchMock).toHaveBeenCalledWith(
    "https://ai.example.test/api/v1/chat/conversations/conversation%2Fone/reset",
    { method: "POST", headers: {}, signal: undefined },
  );
});

describe.each([
  {
    name: "Truncate",
    call: () => truncateConversation("https://ai.example.test", "conv-1", -1),
  },
  {
    name: "Reset",
    call: () => resetConversation("https://ai.example.test", "conv-1"),
  },
])("$name errors", ({ name, call }) => {
  it("preserves the error body", async () => {
    fetchMock.mockResolvedValue(
      new Response("Revision conflict", { status: 409 }),
    );
    await expect(call()).rejects.toThrow("Revision conflict");
  });

  it("reports the status when the error body is empty", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 503 }));
    await expect(call()).rejects.toThrow(`${name} failed with status 503`);
  });

  it("reports the status even if reading the error body fails", async () => {
    const response = new Response(null, { status: 500 });
    jest
      .spyOn(response, "text")
      .mockRejectedValue(new Error("Unreadable body"));
    fetchMock.mockResolvedValue(response);
    await expect(call()).rejects.toThrow(`${name} failed with status 500`);
  });
});

describe("legacy conversation positions", () => {
  it("loads authenticated server positions for repeated saved user messages", async () => {
    const messages = [
      { role: "user" as const, content: "continue" },
      { role: "assistant" as const, content: "answer" },
      { role: "user" as const, content: "continue" },
    ];
    fetchMock.mockResolvedValue(
      Response.json([
        { messageIndex: 0, content: "continue" },
        { messageIndex: 4, content: "continue" },
      ]),
    );
    const indexed = await resolveServerMessageIndexes(
      "https://ai.example.test/",
      "conversation/one",
      messages,
      2,
    );
    expect(indexed[2].serverMessageIndex).toBe(4);
    expect(fetchMock).toHaveBeenCalledWith(
      "https://ai.example.test/api/v1/chat/conversations/conversation%2Fone/user-messages",
      {
        headers: { Authorization: "Bearer test-token" },
      },
    );
    expect(messages[2]).not.toHaveProperty("serverMessageIndex");
  });

  it("keeps positions supplied by SSE without fetching history", async () => {
    const messages = [
      { role: "user" as const, content: "next", serverMessageIndex: 6 },
    ];
    expect(
      await resolveServerMessageIndexes(
        "https://ai.example.test",
        "c",
        messages,
        0,
      ),
    ).toBe(messages);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects an unmatched legacy history without changing local messages", async () => {
    const messages = [{ role: "user" as const, content: "missing" }];
    fetchMock.mockResolvedValue(Response.json([]));
    await expect(
      resolveServerMessageIndexes("https://ai.example.test", "c", messages, 0),
    ).rejects.toThrow("does not match");
    expect(messages[0]).not.toHaveProperty("serverMessageIndex");
  });

  it("reports failures to read the archived history", async () => {
    fetchMock.mockResolvedValue(
      new Response("History unavailable", { status: 503 }),
    );
    await expect(
      resolveServerMessageIndexes(
        "https://ai.example.test",
        "c",
        [{ role: "user", content: "old" }],
        0,
      ),
    ).rejects.toThrow("History unavailable");
  });
});
