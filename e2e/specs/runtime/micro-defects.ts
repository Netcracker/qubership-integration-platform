/**
 * The defects the runtime cases meet on the micro engine.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";
import { NO_LINE_FOR_CHAIN } from "../../support/logs.js";

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

/**
 * What the scheduler case adds to its failure: how many sessions the chain recorded on its domain
 * before the wait, and which other micro domains the catalog lists.
 */
export function firingHistory(chain: string, domain: string, earlier: number, others: readonly string[]): string {
  return `${chain} recorded ${earlier} sessions on ${domain} before the wait and none during it; other micro domains: ${others.join(", ") || "none"}`;
}

/** A history that shows the chain fired on its domain before, beside at least one other micro domain. */
const FIRED_BESIDE_ANOTHER_DOMAIN = /recorded [1-9]\d* sessions on \S+ before the wait and none during it; other micro domains: (?!none)/;

/**
 * A scheduler chain on one micro domain can stop firing while another micro domain runs. Every micro
 * pod starts its Quartz scheduler as `quartz-scheduler`, clustered over the shared `engine.QRTZ_`
 * tables (`QuartzSchedulerProvider`), where the classic engine names its own `engine-<domain>`. All
 * micro domains therefore share one cluster, and a trigger fires on whichever pod acquires it. On a
 * pod with no route for the trigger, Camel's `CamelJob` creates an endpoint with no consumer and
 * drops the firing, logging it only at debug. That cause is read off the code and not reproduced:
 * CI run 38673581259 showed the shared micro domain's `scheduler-basic` record a session every 3 s
 * until 03:15:57, then none for over ten minutes, while its pod stayed up beside the brokers-micro
 * domain's pod and logged 0 lines for the chain over the case's wait. No issue is filed yet.
 *
 * The matcher needs that whole shape: the pod logged nothing for the chain, the chain had recorded
 * sessions on its domain before, and another micro domain runs. A Quartz that never fires on the
 * domain, a wrong cron, or a route that never started leaves no earlier session, and stays a real
 * failure.
 */
export const SCHEDULER_FIRES_ON_ANOTHER_DOMAIN: ConditionalDefect = {
  title: "a micro domain's scheduler trigger fires on another micro domain's pod",
  engine: "micro",
  matches: (message) => NO_LINE_FOR_CHAIN.test(message) && FIRED_BESIDE_ANOTHER_DOMAIN.test(message),
};
