import { createOpencodeClient } from "@opencode-ai/sdk/v2/client";
import { createChatClient } from "../../src/ai/opencode/client.ts";

jest.mock("@opencode-ai/sdk/v2/client", () => ({
  createOpencodeClient: jest.fn(),
}));

it("should allow local HTTP and normalize the endpoint", () => {
  expect(createChatClient("http://127.0.0.1:4096/", "opencode", "").url).toBe(
    "http://127.0.0.1:4096",
  );
  expect(createOpencodeClient).toHaveBeenLastCalledWith(
    expect.objectContaining({ headers: {}, throwOnError: true }),
  );
});

it("should use only OpenCode credentials for a remote HTTPS server", () => {
  createChatClient(
    "https://example.com/opencode/",
    "opencode",
    "test-password",
  );
  expect(createOpencodeClient).toHaveBeenLastCalledWith(
    expect.objectContaining({
      baseUrl: "https://example.com/opencode",
      headers: { Authorization: `Basic ${btoa("opencode:test-password")}` },
    }),
  );
});

it("should omit QIP cookies and reject redirects", async () => {
  const fetchMock = jest
    .spyOn(globalThis, "fetch")
    .mockResolvedValue(new Response());
  createChatClient("https://example.com", "opencode", "");
  const calls = jest.mocked(createOpencodeClient).mock.calls;
  const request = new Request("https://example.com/global/health");
  await calls[calls.length - 1][0]?.fetch?.(request);
  expect(fetchMock).toHaveBeenCalledWith(request, {
    redirect: "error",
    credentials: "omit",
  });
  fetchMock.mockRestore();
});

it.each([
  "http://example.com",
  "file:///tmp",
  "https://user:password@example.com",
  "https://example.com?token=test",
  "https://example.com#secret",
])("should reject unsafe endpoint %s", (url) => {
  expect(() => createChatClient(url, "", "")).toThrow("Use HTTPS");
});
