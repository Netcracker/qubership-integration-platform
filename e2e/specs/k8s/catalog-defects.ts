/**
 * The catalog defects the `k8s` cases meet.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/** The message of the assertion `discovery.spec.ts` makes on the result, which fails only on an empty result. */
export const RESULT_AT_100 = "the discovery result once the progress reads 100";

/**
 * `DiscoveryService.setDiscoveryStatusResult` commits the progress `100` before it writes the result,
 * in a second transaction. A read in between gets the previous run's result, or, where discovery never
 * ran before, 200 with an empty body from `GET /v1/systems/discovery/result`, which the client returns
 * as `undefined`. Only the empty body is pinned. The UI reads the result as soon as the progress
 * reads 100 (`ServiceDiscoveryButton.tsx`), so it meets the same window. No issue is filed yet.
 */
export const DISCOVERY_RESULT_AFTER_PROGRESS: ConditionalDefect = {
  title: "the catalog reports discovery progress 100 before it stores the discovery result",
  engine: "any",
  matches: (message) => message.includes(RESULT_AT_100),
};
