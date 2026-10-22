import { getAiServiceUrl } from "../../ai/appConfig.ts";
import { parseDecisionPayload } from "../../ai/modelProviders/sseParsing.ts";
import type { ChatDecision } from "../../ai/modelProviders/types.ts";

function baseUrl(): string | null {
  const u = getAiServiceUrl();
  return u ? u.replace(/\/$/, "") : null;
}

/**
 * Fetch the decision a conversation is currently waiting on, straight from durable server state.
 * Returns `null` both when the AI service URL is not configured and when the server answers `204`
 * (no open gate) — in both cases there is nothing to show. A non-OK, non-204 response throws
 * instead of returning `null`, so a caller can tell "the server has no open gate" apart from
 * "the request failed" and avoid dropping a gate the transcript still shows over a network hiccup.
 */
export async function fetchOpenDecision(
  conversationId: string,
): Promise<ChatDecision | null> {
  const base = baseUrl();
  if (!base) return null;
  const res = await fetch(`${base}/api/v1/chat/${conversationId}/decision`);
  if (res.status === 204) return null;
  if (!res.ok) {
    throw new Error(`Failed to fetch open decision: ${res.status}`);
  }
  return parseDecisionPayload(await res.text());
}
