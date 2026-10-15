/**
 * The idempotency modes a trigger's cases run: two calls with one key, and what the second one does.
 *
 * `shared/idempotency.hbs` puts the check inside the trigger's step, so `Idempotency` follows the
 * trigger in the trace when the check is on, and `Callee Reply` is the step the sub-chain runs
 * inside the caller's session.
 */
import { covers } from "../registry/covers.js";
import { branchName } from "../fixtures/axis-generator.js";
import type { ExecutionStatus } from "./sessions.js";

export const IDEMPOTENCY_STEP = "Idempotency";

export interface IdempotencyMode {
  axisPath: "idempotency/enabled" | "idempotency/actionOnDuplicate";
  value: boolean | string;
  /** The fixture name's suffix. */
  fixture: string;
  /** What the second call does, for the title. */
  outcome: string;
  executionStatus: ExecutionStatus;
  /** The second session's steps after the trigger. */
  steps: string[];
  /** The exception the `Idempotency` step records. */
  failure?: string;
}

/** The four modes, for fixtures whose chain runs `downstream` after the trigger when the check lets it through. */
export function idempotencyModes(downstream: string): IdempotencyMode[] {
  return [
    { axisPath: "idempotency/enabled", value: false, fixture: "disabled", outcome: "runs the chain again", executionStatus: "COMPLETED_NORMALLY", steps: [downstream] },
    { axisPath: "idempotency/actionOnDuplicate", value: "ignore", fixture: "ignore", outcome: "stops at the check", executionStatus: "COMPLETED_NORMALLY", steps: [IDEMPOTENCY_STEP] },
    { axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", fixture: "throw-exception", outcome: "fails at the check", executionStatus: "COMPLETED_WITH_ERRORS", steps: [IDEMPOTENCY_STEP], failure: "Duplicated idempotency key" },
    { axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", fixture: "execute-subchain", outcome: "runs the sub-chain instead", executionStatus: "COMPLETED_NORMALLY", steps: [IDEMPOTENCY_STEP, "Callee Reply"] },
  ];
}

/** Declares the rows a passing case of `mode` proves on `family`. */
export function coversIdempotency(family: string, mode: IdempotencyMode): void {
  covers(family, mode.axisPath, mode.value);
  // Every actionOnDuplicate chain enables the check.
  if (mode.axisPath === "idempotency/actionOnDuplicate") covers(family, "idempotency/enabled", true);
}

/** The first session's steps: the trigger, the check when it is on, and `downstream`. */
export function firstSteps(mode: IdempotencyMode, downstream: string): string[] {
  return [branchName(mode), ...(mode.value !== false ? [IDEMPOTENCY_STEP] : []), downstream];
}
