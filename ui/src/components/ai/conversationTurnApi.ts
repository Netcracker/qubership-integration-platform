import { getHeadersForContext } from "../../api/rest/requestHeadersInterceptor.ts";
import { buildTruncateBody } from "./conversationTurnIndex.ts";

function getBearerHeader(
  serviceUrl: string,
  path: string,
): Record<string, string> {
  const base = serviceUrl.replace(/\/$/, "");
  const url = `${base}${path}`;
  const raw = getHeadersForContext({ url, baseURL: base });
  const auth = raw?.Authorization;
  if (typeof auth !== "string") {
    return {};
  }
  return { Authorization: auth };
}

export async function truncateConversation(
  serviceUrl: string,
  conversationId: string,
  afterMessageIndex: number,
  signal?: AbortSignal,
): Promise<void> {
  const base = serviceUrl.replace(/\/$/, "");
  const path = `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/truncate`;
  const url = `${base}${path}`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...getBearerHeader(serviceUrl, path),
    },
    body: JSON.stringify(buildTruncateBody(afterMessageIndex)),
    signal,
  });
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `Truncate failed with status ${response.status}`);
  }
}

export async function resetConversation(
  serviceUrl: string,
  conversationId: string,
  signal?: AbortSignal,
): Promise<void> {
  const base = serviceUrl.replace(/\/$/, "");
  const path = `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/reset`;
  const url = `${base}${path}`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      ...getBearerHeader(serviceUrl, path),
    },
    signal,
  });
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `Reset failed with status ${response.status}`);
  }
}
