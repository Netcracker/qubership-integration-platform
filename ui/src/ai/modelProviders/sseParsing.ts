import type {
  ActivityStepPayload,
  CatalogSystemType,
  CatalogChangePayload,
  ChatDecision,
  ChatDecisionSpec,
  StreamingChunk,
} from "./types.ts";

export function parseCatalogChange(
  value: unknown,
): CatalogChangePayload | null {
  if (typeof value !== "object" || value === null) return null;
  const data = value as Record<string, unknown>;
  if (typeof data.chainsListChanged !== "boolean") return null;
  if (
    data.chainIds !== null &&
    (!Array.isArray(data.chainIds) ||
      !data.chainIds.every(
        (id) => typeof id === "string" && id.trim().length > 0,
      ))
  )
    return null;
  return {
    chainIds:
      data.chainIds === null ? null : [...new Set(data.chainIds as string[])],
    chainsListChanged: data.chainsListChanged,
  };
}

function parseCatalogSystemType(value: unknown): CatalogSystemType | undefined {
  return value === "EXTERNAL" || value === "INTERNAL" ? value : undefined;
}

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
    if (
      kind !== "skill" &&
      kind !== "pipeline" &&
      kind !== "tool" &&
      kind !== "llm"
    ) {
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
    if (
      typeof parsed.actionLabels === "object" &&
      parsed.actionLabels !== null
    ) {
      const labels = parsed.actionLabels as Record<string, unknown>;
      decision.actionLabels = Object.fromEntries(
        decision.actions.flatMap((action) => {
          const label = labels[action];
          return typeof label === "string" && label.trim()
            ? [[action, label]]
            : [];
        }),
      );
    }
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
    if (Array.isArray(parsed.chainIds)) {
      const chainIds = parsed.chainIds.filter(
        (item): item is string => typeof item === "string" && item.length > 0,
      );
      if (chainIds.length > 0) {
        decision.chainIds = chainIds;
      }
    }
    if (isRecoveryPayload(parsed.recovery)) {
      decision.recovery = normalizeRecovery(parsed.recovery);
    }
    if (Array.isArray(parsed.specs)) {
      const specs: ChatDecisionSpec[] = parsed.specs.flatMap((item) => {
        if (typeof item !== "object" || item === null) {
          return [];
        }
        const spec = item as Record<string, unknown>;
        if (typeof spec.s3Key !== "string" || spec.s3Key.length === 0) {
          return [];
        }
        const displayName =
          typeof spec.displayName === "string" && spec.displayName.length > 0
            ? spec.displayName
            : spec.s3Key;
        const systemType = parseCatalogSystemType(spec.systemType);
        const entry: ChatDecisionSpec = {
          s3Key: spec.s3Key,
          displayName,
          ...(systemType === undefined ? {} : { systemType }),
        };
        return [entry];
      });
      if (specs.length > 0) {
        decision.specs = specs;
      }
    }
    return decision;
  } catch {
    return null;
  }
}

const RECOVERY_CATEGORIES = new Set([
  "temporary-technical-failure",
  "regeneratable-execution-failure",
  "requirement-brief-defect",
  "design-artifact-defect",
  "plan-artifact-defect",
  "permanent-environment-failure",
  "internal-service-failure",
  "repair-allowance-required",
  "repeated-identical-failure",
  "unclassified-failure",
]);

function isOptionalNumber(value: unknown): boolean {
  return value === undefined || value === null || typeof value === "number";
}

function isOptionalString(value: unknown): boolean {
  return value === undefined || value === null || typeof value === "string";
}

function isRecoveryPayload(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null) return false;
  const recovery = value as Record<string, unknown>;
  return (
    typeof recovery.category === "string" &&
    RECOVERY_CATEGORIES.has(recovery.category) &&
    typeof recovery.title === "string" &&
    typeof recovery.summary === "string" &&
    typeof recovery.preservedWork === "string" &&
    isOptionalString(recovery.technicalDetails) &&
    isOptionalNumber(recovery.retryDelayMs) &&
    isOptionalString(recovery.runId) &&
    isOptionalString(recovery.failedStageId)
  );
}

function normalizeRecovery(
  recovery: Record<string, unknown>,
): NonNullable<ChatDecision["recovery"]> {
  const retryDelayMs =
    typeof recovery.retryDelayMs === "number"
      ? recovery.retryDelayMs
      : undefined;
  const runId = typeof recovery.runId === "string" ? recovery.runId : undefined;
  const failedStageId =
    typeof recovery.failedStageId === "string"
      ? recovery.failedStageId
      : undefined;
  return {
    category: recovery.category as NonNullable<
      ChatDecision["recovery"]
    >["category"],
    title: recovery.title as string,
    summary: recovery.summary as string,
    preservedWork: recovery.preservedWork as string,
    technicalDetails:
      typeof recovery.technicalDetails === "string"
        ? recovery.technicalDetails
        : "",
    ...(retryDelayMs === undefined ? {} : { retryDelayMs }),
    ...(runId === undefined ? {} : { runId }),
    ...(failedStageId === undefined ? {} : { failedStageId }),
  };
}

/**
 * Split a decoded SSE buffer into complete frames. Normalize CRLF so a frame
 * is emitted as soon as the blank-line delimiter arrives.
 */
export function splitSseFrames(buffer: string): {
  complete: string[];
  rest: string;
} {
  const trailingCarriageReturn = buffer.endsWith("\r");
  const normalized = (trailingCarriageReturn ? buffer.slice(0, -1) : buffer)
    .replaceAll("\r\n", "\n")
    .replaceAll("\r", "\n");
  const parts = normalized.split("\n\n");
  const rest = parts.pop() ?? "";
  return {
    complete: parts.filter((part) => part.trim().length > 0),
    rest: rest + (trailingCarriageReturn ? "\r" : ""),
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
        const parsed = JSON.parse(payload) as {
          conversationId?: unknown;
          supportsCatalogChangeEvents?: unknown;
        };
        if (typeof parsed.conversationId === "string") {
          return [
            {
              type: "meta",
              conversationId: parsed.conversationId,
              ...(typeof parsed.supportsCatalogChangeEvents === "boolean"
                ? {
                    supportsCatalogChangeEvents:
                      parsed.supportsCatalogChangeEvents,
                  }
                : {}),
            },
          ];
        }
      } catch {
        return [];
      }
      return [];
    }

    case "token":
      return [{ type: "delta", contentDelta: payload }];

    case "catalog-changed": {
      try {
        const catalogChange = parseCatalogChange(JSON.parse(payload));
        if (catalogChange) return [{ type: "catalog-changed", catalogChange }];
      } catch {
        /* Report invalid payloads without guessing their scope. */
      }
      console.warn("[AI] Ignoring invalid catalog change event");
      return [];
    }

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
