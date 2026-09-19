import type {
  ActivityStepPayload,
  ChatMessage,
  PersistedActivitySnapshot,
} from "../../../ai/modelProviders/types.ts";

export type { PersistedActivitySnapshot };

/** Visual card variant for the Activity UI (maps from SSE `kind`). */
export type ActivityVisualKind = "skill" | "tool" | "api" | "ai";

export function formatActivityDuration(durationMs: number): string {
  if (durationMs < 60_000) {
    const seconds = durationMs / 1000;
    const rounded =
      seconds >= 10 ? Math.round(seconds).toString() : seconds.toFixed(1);
    const normalized = rounded.replace(/\.0$/, "");
    return `${normalized}s`;
  }
  const totalSeconds = Math.round(durationMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds}s`;
}

export function buildActivitySummary(
  steps: ActivityStepPayload[],
  durationMs?: number,
): string {
  const toolCount = steps.filter((step) => step.kind === "tool").length;
  const parentCount = steps.filter(
    (step) => step.kind === "skill" || step.kind === "pipeline",
  ).length;

  const parts: string[] = [];
  if (toolCount > 0) {
    parts.push(`${toolCount} tool${toolCount === 1 ? "" : "s"}`);
  } else if (parentCount > 0) {
    parts.push(`${parentCount} step${parentCount === 1 ? "" : "s"}`);
  } else if (steps.length > 0) {
    parts.push(`${steps.length} step${steps.length === 1 ? "" : "s"}`);
  }

  if (durationMs !== undefined && durationMs >= 0) {
    parts.push(formatActivityDuration(durationMs));
  }

  return parts.join(" · ") || "Activity";
}

/**
 * Maps SSE kind to UI visual variant.
 * There is no separate API kind in the payload; pipeline fills the third slot.
 */
export function resolveActivityVisualKind(
  kind: ActivityStepPayload["kind"],
): ActivityVisualKind {
  switch (kind) {
    case "skill":
      return "skill";
    case "tool":
      return "tool";
    case "pipeline":
      return "api";
    case "llm":
      return "ai";
    default: {
      const _exhaustive: never = kind;
      return _exhaustive;
    }
  }
}

/**
 * SSE can leave an earlier skill `running` after the next skill has already started. Show that
 * earlier skill as completed unless one of its nested tools is still running.
 */
export function resolveDisplayedActivityStatus(
  row: ActivityStepPayload,
  rows: ActivityStepPayload[],
  inFlight = false,
): ActivityStepPayload["status"] {
  if (inFlight && row.status === "error") {
    return "running";
  }
  if (row.status !== "running" || row.kind === "tool" || row.kind === "llm") {
    return row.status;
  }
  const hasRunningChild = rows.some(
    (candidate) =>
      candidate.parentId === row.id && candidate.status === "running",
  );
  if (hasRunningChild) {
    return "running";
  }
  const rowIndex = rows.findIndex((candidate) => candidate.id === row.id);
  if (rowIndex < 0) {
    return row.status;
  }
  const laterParentStarted = rows
    .slice(rowIndex + 1)
    .some((candidate) => candidate.kind === "skill");
  return laterParentStarted ? "completed" : "running";
}

/** True when any activity row currently looks in progress to the user. */
export function hasDisplayedRunningActivity(
  rows: ActivityStepPayload[],
  inFlight = false,
): boolean {
  return rows.some(
    (row) => resolveDisplayedActivityStatus(row, rows, inFlight) === "running",
  );
}

export function shouldShowTurnContinuation(
  rows: ActivityStepPayload[],
  inFlight: boolean,
): boolean {
  return (
    inFlight && rows.length > 0 && !hasDisplayedRunningActivity(rows, inFlight)
  );
}

export function resolveErrorRecoveryParentId(
  rows: ActivityStepPayload[],
): string | undefined {
  const errorRow = [...rows].reverse().find((row) => row.status === "error");
  if (!errorRow) {
    return undefined;
  }
  if (errorRow.kind === "skill" || errorRow.kind === "pipeline") {
    return errorRow.id;
  }
  return errorRow.parentId ?? errorRow.id;
}

export function shouldShowErrorRecoveryPass(
  rows: ActivityStepPayload[],
  inFlight: boolean,
): boolean {
  if (!inFlight) {
    return false;
  }
  const hasError = rows.some((row) => row.status === "error");
  const hasRunningLlm = rows.some(
    (row) => row.kind === "llm" && row.status === "running",
  );
  return hasError && !hasRunningLlm;
}

export function visualKindBadgeLabel(visual: ActivityVisualKind): string {
  switch (visual) {
    case "skill":
      return "skill";
    case "tool":
      return "tool";
    case "api":
      return "api";
    case "ai":
      return "AI";
    default: {
      const _exhaustive: never = visual;
      return _exhaustive;
    }
  }
}

export function attachActivityToLastAssistant(
  messages: ChatMessage[],
  steps: ActivityStepPayload[],
  durationMs?: number,
): ChatMessage[] {
  if (steps.length === 0) {
    return messages;
  }

  // Prefer the last narrative assistant; fall back to an error bubble if that is all we have.
  let targetIndex = -1;
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    if (messages[i].role !== "assistant") continue;
    if (messages[i].variant !== "error") {
      targetIndex = i;
      break;
    }
    if (targetIndex < 0) {
      targetIndex = i;
    }
  }
  if (targetIndex < 0) {
    return messages;
  }

  const activity: PersistedActivitySnapshot = {
    steps: steps.map((step) => ({ ...step })),
    summary: buildActivitySummary(steps, durationMs),
    collapsed: true,
  };
  const next = [...messages];
  next[targetIndex] = { ...messages[targetIndex], activity };
  return next;
}
