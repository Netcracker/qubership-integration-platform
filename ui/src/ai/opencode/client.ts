import { createOpencodeClient } from "@opencode-ai/sdk/v2/client";

export function createChatClient(
  url: string,
  username: string,
  password: string,
) {
  const endpoint = new URL(url.trim());
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(endpoint.hostname);
  if (
    (endpoint.protocol !== "https:" &&
      !(local && endpoint.protocol === "http:")) ||
    endpoint.username ||
    endpoint.password ||
    endpoint.search ||
    endpoint.hash
  ) {
    throw new Error(
      "Use HTTPS for remote OpenCode servers or HTTP on localhost. Enter credentials in the login fields.",
    );
  }
  const baseUrl = endpoint.toString().replace(/\/$/, "");
  const headers: Record<string, string> = {};
  if (password) {
    const bytes = new TextEncoder().encode(`${username}:${password}`);
    headers.Authorization = `Basic ${btoa(String.fromCharCode(...bytes))}`;
  }
  return {
    url: baseUrl,
    client: createOpencodeClient({
      baseUrl,
      headers,
      throwOnError: true,
      // OpenCode authentication must stay separate from the QIP session.
      fetch: (request) =>
        fetch(request, { redirect: "error", credentials: "omit" }),
    }),
  };
}

export function chatError(error: unknown): string {
  if (error instanceof TypeError)
    return "Cannot reach OpenCode. Check the server address, authentication, and CORS settings, then reconnect.";
  if (error instanceof Error) return error.message;
  if (error && typeof error === "object" && "data" in error) {
    const data = error.data;
    if (
      data &&
      typeof data === "object" &&
      "message" in data &&
      typeof data.message === "string"
    )
      return data.message;
  }
  return "OpenCode request failed. Check the server address, authentication, and CORS settings, then reconnect.";
}
