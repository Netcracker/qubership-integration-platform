/**
 * The defects the runtime cases meet on the micro engine.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";
import { NO_LINE_FOR_CHAIN } from "../../support/logs.js";

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
 * pod with no route for the trigger, Camel's `CamelJob` drops the firing and logs it only at debug,
 * and the micro engine's `camel-job-filter` drops the `No CamelContext could be found` error such a
 * firing can raise. That cause is read off the code and not reproduced: CI run 38673581259 showed the
 * shared micro domain's `scheduler-basic` record a session every 3 s until 03:15:57, then none for
 * over ten minutes, while its pod stayed up beside the brokers-micro domain's pod and logged 0 lines
 * for the chain over the case's wait. Filed as #1112.
 *
 * The matcher needs the pod to have logged nothing for the chain, the chain to have recorded sessions
 * on its domain before the wait, and the catalog to list another micro domain. None of that shows
 * another pod acquired the trigger, and nothing the suite reads would: it reads no database (rule
 * 16), and no pod logs the dropped firing at INFO. On a k8s run with brokers the brokers-micro domain
 * runs, and `scheduler-basic` fires every 3 s from its deploy, so the last two clauses always hold.
 * There the pin turns every micro scheduler that stops firing after its first tick into an expected
 * failure, whatever the cause. Only a scheduler that never fired on its domain stays red.
 */
export const SCHEDULER_FIRES_ON_ANOTHER_DOMAIN: ConditionalDefect = {
  title: "a micro domain's scheduler trigger fires on another micro domain's pod",
  engine: "micro",
  matches: (message) => NO_LINE_FOR_CHAIN.test(message) && FIRED_BESIDE_ANOTHER_DOMAIN.test(message),
};
