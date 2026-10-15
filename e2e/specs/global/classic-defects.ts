/**
 * The defects the `global` cases meet on the classic engine.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/**
 * The classic engine answers `limit=0` on `GET /v1/engine/live-exchanges` with 500. `@Positive` throws
 * `ConstraintViolationException`, which `ControllerExceptionHandler` has no handler for, so its
 * catch-all for `Exception` answers 500. The catalog and the micro engine answer the same violation
 * with 400. No issue is filed yet.
 */
export const LIMIT_REFUSED_WITH_500: ConditionalDefect = {
  title: "the classic engine answers limit=0 with 500 where the catalog and the micro engine answer 400",
  engine: "classic",
  matches: (message) =>
    message.includes("must be greater than 0") && message.includes("Expected: 400") && message.includes("Received: 500"),
};
