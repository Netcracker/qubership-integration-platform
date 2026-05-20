import type { ChatMessage } from "../../ai/modelProviders/types.ts";
import type { ChainModificationProposal } from "./ChainModificationConfirmation.tsx";

export function findChainModificationProposalRange(
  content: string,
): { start: number; end: number } | null {
  const marker = '"type":"chain-modification-proposal"';
  const idx = content.indexOf(marker);
  if (idx === -1) return null;
  const start = content.lastIndexOf("{", idx);
  if (start === -1) return null;
  for (let end = content.length; end > start + marker.length; end--) {
    const slice = content.slice(start, end);
    try {
      const parsed: unknown = JSON.parse(slice);
      if (
        parsed &&
        typeof parsed === "object" &&
        (parsed as { type?: unknown }).type === "chain-modification-proposal"
      ) {
        return { start, end };
      }
    } catch {
      // continue
    }
  }
  return null;
}

export function replaceChainModificationProposalForDisplay(
  content: string,
): string {
  const range = findChainModificationProposalRange(content);
  if (!range) return content;
  const before = content.slice(0, range.start).trimEnd();
  const after = content.slice(range.end).trimStart();
  const summary =
    "\n\n*Chain modification proposal – use **Apply changes** to confirm.*\n\n";
  return [before, summary, after]
    .join("")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export function looksLikePlanResponse(content: string): boolean {
  if (!content || content.length < 30) return false;
  const lower = content.toLowerCase();
  const hasNumberedList =
    (/\n\s*1\.\s/.test(content) &&
      (/\n\s*2\.\s/.test(content) || /\n\s*[2-9]\.\s/.test(content))) ||
    /\n\s*1\)\s/.test(content) ||
    /^1\.\s/m.test(content);
  const invitesConfirm =
    lower.includes("agree") ||
    lower.includes("execute plan") ||
    lower.includes("execute the plan") ||
    lower.includes("proceed") ||
    lower.includes("confirm");
  return hasNumberedList && invitesConfirm;
}

export function looksLikeValidationResult(content: string): boolean {
  if (!content) return false;
  const lower = content.toLowerCase();
  return (
    (lower.includes("chain validation completed") ||
      lower.includes("validation completed")) &&
    lower.includes("navigate to") &&
    lower.includes("sessions")
  );
}

export function lastUserMessageIsAgree(messages: ChatMessage[]): boolean {
  const lastUser = [...messages].reverse().find((m) => m.role === "user");
  if (!lastUser || typeof lastUser.content !== "string") return false;
  const t = lastUser.content.trim().toLowerCase();
  return (
    t === "agree" ||
    t === "execute" ||
    t === "execute the plan" ||
    t === "proceed" ||
    t === "yes" ||
    (t.length < 50 && (t.includes("agree") || t.includes("execute")))
  );
}

const ATTACHMENT_URL_PATTERN =
  /https?:\/\/[^\s"'<>)]*\/attachments\/[0-9a-f-]{36}(?:[^\s"'<>)]*)?/gi;

export function extractDesignUrlFromMessages(
  messages: ChatMessage[],
): string | undefined {
  for (const msg of messages) {
    const content = typeof msg.content === "string" ? msg.content : "";
    const match = content.match(ATTACHMENT_URL_PATTERN);
    if (match?.[0]) return match[0];
  }
  return undefined;
}

export function tryParseChainModificationProposal(
  content: string,
): ChainModificationProposal | null {
  const marker = '"type":"chain-modification-proposal"';
  const idx = content.indexOf(marker);
  if (idx === -1) return null;
  const start = content.lastIndexOf("{", idx);
  if (start === -1) return null;
  for (let end = content.length; end > start + marker.length; end--) {
    const slice = content.slice(start, end);
    try {
      const parsed: unknown = JSON.parse(slice);
      if (
        parsed &&
        typeof parsed === "object" &&
        (parsed as { type?: unknown }).type === "chain-modification-proposal"
      ) {
        return parsed as ChainModificationProposal;
      }
    } catch {
      // ignore and try shorter slice
    }
  }
  return null;
}
