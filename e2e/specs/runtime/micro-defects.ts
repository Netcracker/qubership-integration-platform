/**
 * The defects the runtime cases meet on the micro engine.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/** What the egress gateway answers for a route whose upstream it cannot reach. */
const EGRESS_NO_ROUTE = /no healthy upstream/;

/** What a `contain` rule writes when it read the catalog health document `http-out` calls, not the mock. */
const HEALTH_ANSWERED = /'\{"status":"[A-Z_]+".*' doesn't contain '"mocked":/;

/**
 * The micro engine sends a test case run's service call or http sender call to its own address
 * rather than to the endpoint mock. Measured on a micro domain: the pod logs `Endpoint mocking is
 * enabled`, and the call it then sends fails with `UnknownHostException: e2e-service-call.invalid`,
 * or `e2e-http-sender.invalid` for the sender. No issue is filed yet. The engine answers that
 * exception with QIP-0101; an EXTERNAL call reaches the egress gateway instead, which cannot reach
 * the address either. The address of the `http-out` sender resolves, so its test case run gets the
 * catalog health document, and the run's rule reports that body.
 */
export const MOCK_BYPASSED: ConditionalDefect = {
  title: "the micro engine sends a mocked service call to the real address",
  engine: "micro",
  matches: (message) => message.includes("QIP-0101") || EGRESS_NO_ROUTE.test(message) || HEALTH_ANSWERED.test(message),
};

/**
 * The micro engine stores a context value outside a transaction. `ContextStorageService.storeValue`
 * calls `persist` with no `@Transactional` around it, so every SET fails, and the step records
 * `Transaction is not active, consider adding @Transactional to your method to automatically
 * activate one.` Measured on a micro domain; the classic engine stores the value. No issue is filed
 * yet.
 */
export const CONTEXT_STORE_UNTRANSACTED: ConditionalDefect = {
  title: "the micro engine stores a context value outside a transaction",
  engine: "micro",
  matches: (message) => message.includes("Transaction is not active"),
};
