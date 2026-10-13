/**
 * The `since` a spec hands `env.logs`.
 *
 * Rule 18 makes the scoped window a suite-wide idiom, and every window is opened the same way, so
 * the slack below is decided once rather than re-argued at each call site that opens one.
 */

/**
 * How far back a log window opens, in milliseconds.
 *
 * The container's clock and this process's are not the same clock, so a window that starts at this
 * instant can begin after a line the spec is about to cause. A second is far wider than any drift
 * measured on this stack and far narrower than the gap to whatever ran before the case.
 */
const LOG_CLOCK_SLACK = 1_000;

/** Opens a log window now, in the ISO form `env.logs` takes. */
export function logWindowStart(): string {
  return new Date(Date.now() - LOG_CLOCK_SLACK).toISOString();
}
