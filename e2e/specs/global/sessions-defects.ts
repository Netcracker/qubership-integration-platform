/**
 * The sessions-management defects the `global` cases meet.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/** The message of the status assertion `sessions-destructive.spec.ts` makes on the bare delete. */
export const WIPE_ANSWERED = "the bare delete answered";

/**
 * `SessionService.deleteAllSessions` sends `_delete_by_query` without `conflicts=proceed`, so OpenSearch
 * aborts the delete on the first session element the engine rewrote after the delete's search, and
 * answers 409 with `version_conflict_engine_exception`. `delete` wraps that in `SearchException`, which
 * `GlobalExceptionHandler` answers with 400. The Sessions page meets it whenever a chain runs during a
 * delete of every session. Measured in CI run 38651497943: 4121 of 4123 documents deleted, 2 conflicts.
 * No issue is filed yet.
 */
export const WIPE_ABORTED_ON_CONFLICT: ConditionalDefect = {
  title: "the bare session delete aborts with 400 when the engine rewrites a session element during it",
  engine: "any",
  matches: (message) =>
    message.includes(WIPE_ANSWERED) &&
    message.includes("version_conflict_engine_exception") &&
    message.includes("Received: 400"),
};
