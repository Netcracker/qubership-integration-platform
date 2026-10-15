/**
 * The defects the broker cases meet on the micro copy, and the selectors of the engine log lines that
 * name them.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import { strikesAsKnown, type ConditionalDefect } from "../../support/known-defect.js";
import { NO_LINE_FOR_CHAIN, withEngineLines } from "../../support/logs.js";
import type { Env } from "../../env/index.js";

const PUBSUB_UNAUTHENTICATED = "UNAUTHENTICATED: Failed computing credential metadata";

/**
 * The micro engine connects `google-pubsub` to Google rather than to the emulator. Measured on a micro
 * domain running the `development` profile, which sets `cip.pubsub.emulator.enabled`: a sender step
 * records `UnauthenticatedException: … UNAUTHENTICATED: Failed computing credential metadata`, the
 * token request goes to `https://oauth2.googleapis.com/token`, and a trigger's subscriber logs the
 * same failure every few seconds. The classic engine reaches the emulator. No issue is filed yet.
 */
export const PUBSUB_EMULATOR_BYPASSED: ConditionalDefect = {
  title: "the micro engine sends pub/sub calls to Google instead of the emulator",
  engine: "micro",
  matches: (message) => message.includes(PUBSUB_UNAUTHENTICATED),
};

/** Selects the engine log lines that carry the pub/sub authentication failure. */
export function pubsubAuthFailure(line: string): boolean {
  return line.includes(PUBSUB_UNAUTHENTICATED);
}

/** Runs a pub/sub trigger case's wait; a failure carries the subscriber's authentication failures. */
export async function pubsubDelivered<T>(env: Env, since: string, wait: () => Promise<T>): Promise<T> {
  return strikesAsKnown(PUBSUB_EMULATOR_BYPASSED, () => withEngineLines(env, since, "naming UNAUTHENTICATED", pubsubAuthFailure, wait));
}

/**
 * An `sftp-trigger-2` on a micro domain never polls its directory. Measured on a micro domain: each
 * consumer logs in once at start and creates its directory, then the SFTP server sees no other login
 * from the pod, a dropped file records no session, and the engine logs no line for the chain. The
 * classic engine polls every 3 s. No issue is filed yet.
 */
export const SFTP_TRIGGER_IDLE: ConditionalDefect = {
  title: "an sftp-trigger-2 on the micro engine never polls its directory",
  engine: "micro",
  matches: (message) => NO_LINE_FOR_CHAIN.test(message),
};
