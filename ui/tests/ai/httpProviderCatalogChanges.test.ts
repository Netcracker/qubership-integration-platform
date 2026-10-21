import { HttpAiModelProvider } from "../../src/ai/modelProviders/httpProvider.ts";
import type { StreamingChunk } from "../../src/ai/modelProviders/types.ts";

const originalFetch = global.fetch;
afterEach(() => {
  global.fetch = originalFetch;
});

function stream(parts: string[]) {
  let next = 0;
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        body: {
          getReader: () => ({
            read: async () =>
              next < parts.length
                ? {
                    done: false,
                    value: new TextEncoder().encode(parts[next++]),
                  }
                : { done: true },
          }),
        },
      }) as unknown as Response,
  );
}

it("should deliver catalog changes between text chunks without persisting them as messages", async () => {
  stream([
    'event: meta\r\ndata: {"conversationId":"a","supportsCatalogChangeEvents":true}\r\n\r',
    '\nevent: token\r\ndata: Saved\r\n\r\nevent: catalog-changed\r\ndata: {"chainIds":["chain-1"],"chainsListChanged":true}\r\n\r\nevent: done\r\ndata: a\r\n\r\n',
  ]);
  const chunks: StreamingChunk[] = [];
  const result = await new HttpAiModelProvider(
    "http://ai.example",
  ).chatWithProgress(
    { messages: [{ role: "user", content: "save" }] },
    (chunk) => chunks.push(chunk),
  );
  expect(chunks.map((chunk) => chunk.type)).toEqual([
    "meta",
    "delta",
    "catalog-changed",
    "done",
  ]);
  expect(chunks[0].supportsCatalogChangeEvents).toBe(true);
  expect(result.messages).toEqual([
    { role: "user", content: "save" },
    { role: "assistant", content: "Saved" },
  ]);
});

it("should reject an incomplete stream instead of leaving progress pending", async () => {
  stream(["event: token\ndata: partial\n\n"]);
  const chunks: StreamingChunk[] = [];
  await expect(
    new HttpAiModelProvider("http://ai.example").chatWithProgress(
      { messages: [] },
      (chunk) => chunks.push(chunk),
    ),
  ).rejects.toThrow("before the server confirmed completion");
  expect(chunks.at(-1)?.transportError).toBe(true);
});

it("should settle progress after cancellation", async () => {
  stream([]);
  const controller = new AbortController();
  controller.abort();
  await expect(
    new HttpAiModelProvider("http://ai.example").chatWithProgress(
      { messages: [], abortSignal: controller.signal },
      () => undefined,
    ),
  ).rejects.toMatchObject({ name: "AbortError" });
});

it("should reject a malformed catalog event without applying a guessed scope", async () => {
  stream([
    'event: catalog-changed\ndata: {"chainIds":[5],"chainsListChanged":true}\n\nevent: done\ndata: a\n\n',
  ]);
  const warn = jest.spyOn(console, "warn").mockImplementation(() => undefined);
  const chunks: StreamingChunk[] = [];
  try {
    await new HttpAiModelProvider("http://ai.example").streamChat(
      { messages: [] },
      (chunk) => chunks.push(chunk),
    );
    expect(chunks.map((chunk) => chunk.type)).toEqual(["done"]);
    expect(warn).toHaveBeenCalled();
  } finally {
    warn.mockRestore();
  }
});
