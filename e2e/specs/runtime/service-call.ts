/**
 * What the service-call specs share: the body a mock answers, and the defect that keeps the micro
 * engine from reaching the mock, which misc-elements.spec.ts and testing-service-runs.spec.ts meet
 * too.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/** What an http mock answers. */
export const MOCKED_BODY = '{"mocked":"http"}';

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
  matches: (message) => message.includes("QIP-0101") || EGRESS_NO_ROUTE.test(message) || HEALTH_ANSWERED.test(message),
};
