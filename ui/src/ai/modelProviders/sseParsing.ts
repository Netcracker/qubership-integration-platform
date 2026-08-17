import type {
  ActivityStepPayload,
  ChatDecision,
  StreamingChunk,
} from "./types.ts";

function parseStepPayload(payload: string): ActivityStepPayload | null {
  try {
    const parsed = JSON.parse(payload) as Record<string, unknown>;
    const id = parsed.id;
    const kind = parsed.kind;
    const status = parsed.status;
    if (
      typeof id !== "string" ||
      typeof kind !== "string" ||
      typeof status !== "string"
    ) {
      return null;
    }
    if (kind !== "skill" && kind !== "pipeline" && kind !== "tool") {
      return null;
    }
    if (
      status !== "running" &&
      status !== "completed" &&
      status !== "error" &&
      status !== "cancelled"
    ) {
      return null;
    }
    const step: ActivityStepPayload = {
      id,
      kind,
      status,
    };
    if (typeof parsed.label === "string") {
      step.label = parsed.label;
    }
    if (typeof parsed.parentId === "string") {
      step.parentId = parsed.parentId;
    } else if (parsed.parentId === null) {
      step.parentId = null;
    }
    return step;
  } catch {
    return null;
  }
}

/**
 * Parse a decision payload, tolerating the JSON `null` the server sends for absent optional
 * fields. Shared by the SSE `decision` event and the `GET .../decision` reconciliation endpoint,
 * which carry the same JSON shape.
 */
export function parseDecisionPayload(payload: string): ChatDecision | null {
  try {
    const parsed = JSON.parse(payload) as Record<string, unknown>;
    const id = parsed.id;
    const kind = parsed.kind;
    if (typeof id !== "string" || (kind !== "approve" && kind !== "clarify")) {
      return null;
    }
    const decision: ChatDecision = {
      id,
      kind,
      question: typeof parsed.question === "string" ? parsed.question : "",
      actions: Array.isArray(parsed.actions)
        ? parsed.actions.filter(
            (action): action is string => typeof action === "string",
          )
        : [],
    };
    if (typeof parsed.artifactType === "string") {
      decision.artifactType = parsed.artifactType;
    }
    if (typeof parsed.artifactHash === "string") {
      decision.artifactHash = parsed.artifactHash;
    }
    if (typeof parsed.revision === "number") {
      decision.revision = parsed.revision;
    }
    if (typeof parsed.reason === "string") {
      decision.reason = parsed.reason;
    }
    if (Array.isArray(parsed.missingEvidence)) {
      decision.missingEvidence = parsed.missingEvidence.filter(
        (item): item is string => typeof item === "string",
      );
    }
    return decision;
  } catch {
    return null;
  }
}

/**
 * Split a decoded SSE buffer into complete frames. Normalize CRLF so a frame
 * is emitted as soon as the blank-line delimiter arrives.
 */
export function splitSseFrames(buffer: string): {
  complete: string[];
  rest: string;
} {
  const normalized = buffer.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
  const parts = normalized.split("\n\n");
  const rest = parts.pop() ?? "";
  return {
    complete: parts.filter((part) => part.trim().length > 0),
    rest,
  };
}

/** Parse one SSE block (`event:` + `data:` lines) into zero or more streaming chunks. */
export function parseCipSseBlock(block: string): StreamingChunk[] {
  let eventType: string | null = null;
  const dataLines: string[] = [];

  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      eventType = line.slice("event:".length).replace(/^ /, "").trimEnd();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).replace(/^ /, ""));
    }
  }

  if (!eventType && dataLines.length > 0) {
    const nestedDataLines: string[] = [];
    for (const nested of dataLines) {
      if (nested.startsWith("event:")) {
        eventType = nested.slice("event:".length).replace(/^ /, "").trimEnd();
      } else if (nested.startsWith("data:")) {
        nestedDataLines.push(nested.slice("data:".length).replace(/^ /, ""));
      } else if (nested.length > 0) {
        nestedDataLines.push(nested);
      }
    }
    dataLines.length = 0;
    dataLines.push(...nestedDataLines);
  }

  if (!eventType || dataLines.length === 0) {
    return [];
  }

  const payload = dataLines
    .filter((line) => !line.startsWith("event:") && !line.startsWith("data:"))
    .join("\n");

  switch (eventType) {
    case "meta": {
      try {
        const parsed = JSON.parse(payload) as { conversationId?: unknown };
        if (typeof parsed.conversationId === "string") {
          return [{ type: "meta", conversationId: parsed.conversationId }];
        }
      } catch {
        return [];
      }
      return [];
    }

    case "token":
      return [{ type: "delta", contentDelta: payload }];

    case "step": {
      const step = parseStepPayload(payload);
      return step ? [{ type: "step", step }] : [];
    }

    case "decision": {
      const decision = parseDecisionPayload(payload);
      return decision ? [{ type: "decision", decision }] : [];
    }

    case "done":
      return [
        {
          type: "done",
          conversationId: payload.trim() || undefined,
          finishReason: "stop",
        },
      ];

    case "error":
      return [{ type: "error", errorMessage: payload || "Unknown error" }];

    default:
      return [];
  }
}
