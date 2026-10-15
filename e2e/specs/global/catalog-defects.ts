/**
 * The catalog defects the `global` cases meet.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/** What `diagnostic.spec.ts` appends to a findings assertion: the answer's count and its entity list's length. */
export function countedAnswer(alertsCount: number, entities: number): string {
  return `(${alertsCount} alerts, ${entities} entities)`;
}

/**
 * `GET /v1/catalog/diagnostic/validations/{id}` reads the rule's alerts, then their count, then its
 * status, each in its own statement (`DiagnosticController.getValidationById`). A read that straddles
 * the commit of a run's alerts answers the previous run's alerts with the new count and state `OK`,
 * because the run saves `OK` only after that commit. The Validations page reads the same endpoint.
 * Measured in CI run 38680913931: 207 alerts and 206 entities, without the alert the run had just
 * added. No issue is filed yet.
 */
export const VALIDATION_READ_BEFORE_ALERTS: ConditionalDefect = {
  title: "a validation read answers state OK with the alerts of the previous run",
  engine: "any",
  matches: (message) => /\((\d+) alerts, (?!\1 entities)\d+ entities\)/.test(message),
};
