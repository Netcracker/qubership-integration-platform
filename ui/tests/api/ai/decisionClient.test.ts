import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import { setAiServiceUrl } from "../../../src/ai/appConfig.ts";
import { fetchOpenDecision } from "../../../src/api/ai/decisionClient.ts";

function mockFetchOnce(response: Partial<Response>) {
  const fetchMock = jest.fn<typeof fetch>().mockResolvedValue(response as Response);
  global.fetch = fetchMock;
  return fetchMock;
}

describe("fetchOpenDecision", () => {
  beforeEach(() => {
    setAiServiceUrl("https://ai.example.com");
  });

  afterEach(() => {
    setAiServiceUrl(undefined);
  });

  it("should return the parsed decision on a 200 response", async () => {
    const fetchMock = mockFetchOnce({
      status: 200,
      ok: true,
      text: () =>
        Promise.resolve(
          JSON.stringify({
            id: "gate-1",
            kind: "approve",
            question: "Approve the chain revision?",
            actions: ["approve", "request-changes"],
          }),
        ),
    });

    const result = await fetchOpenDecision("conv-1");

    expect(result).toEqual({
      id: "gate-1",
      kind: "approve",
      question: "Approve the chain revision?",
      actions: ["approve", "request-changes"],
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "https://ai.example.com/api/v1/chat/conv-1/decision",
    );
  });

  it("should tolerate JSON null on absent optional fields", async () => {
    mockFetchOnce({
      status: 200,
      ok: true,
      text: () =>
        Promise.resolve(
          JSON.stringify({
            id: "gate-1",
            kind: "clarify",
            question: "What should the retry limit be?",
            artifactType: null,
            reason: null,
            actions: ["approve"],
          }),
        ),
    });

    const result = await fetchOpenDecision("conv-1");

    expect(result?.artifactType).toBeUndefined();
    expect(result?.reason).toBeUndefined();
  });

  it("should return null when the conversation has no open gate", async () => {
    mockFetchOnce({ status: 204, ok: true, text: () => Promise.resolve("") });

    const result = await fetchOpenDecision("conv-1");

    expect(result).toBeNull();
  });

  it("should throw on a server error instead of returning null", async () => {
    mockFetchOnce({ status: 500, ok: false, text: () => Promise.resolve("") });

    // A thrown error, not a null result, keeps a failed fetch from being read as
    // "the server has nothing open" by the caller.
    await expect(fetchOpenDecision("conv-1")).rejects.toThrow();
  });

  it("should return null without calling fetch when no AI service URL is configured", async () => {
    setAiServiceUrl(undefined);
    const fetchMock = jest.fn<typeof fetch>();
    global.fetch = fetchMock;

    const result = await fetchOpenDecision("conv-1");

    expect(result).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
