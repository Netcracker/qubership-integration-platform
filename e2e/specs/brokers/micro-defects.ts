/**
 * The defects the broker cases meet on the micro copy, and the engine log reading that names them.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { Env } from "../../env/index.js";
import type { ConditionalDefect } from "../../support/known-defect.js";

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
  matches: (message) => message.includes(PUBSUB_UNAUTHENTICATED),
};

/** Selects the engine log lines that carry the pub/sub authentication failure. */
export function pubsubAuthFailure(line: string): boolean {
  return line.includes(PUBSUB_UNAUTHENTICATED);
}

const NO_LINE_FOR_CHAIN = /logged 0 lines for chain /;

/**
 * An `sftp-trigger-2` on a micro domain never polls its directory. Measured on a micro domain: each
 * consumer logs in once at start and creates its directory, then the SFTP server sees no other login
 * from the pod, a dropped file records no session, and the engine logs no line for the chain. The
 * classic engine polls every 3 s. No issue is filed yet.
 */
export const SFTP_TRIGGER_IDLE: ConditionalDefect = {
  title: "an sftp-trigger-2 on the micro engine never polls its directory",
  matches: (message) => NO_LINE_FOR_CHAIN.test(message),
};

/** Selects the engine log lines a run of chain `id` writes, by the chain id in their MDC block. */
export function linesOfChain(id: string): (line: string) => boolean {
  return (line) => line.includes(`chain_id=${id}`);
}

/**
 * Runs `wait`, and adds to its failure how many engine log lines since `since` `pick` selects, and the
 * first of them. `what` names the selection in that message.
 */
export async function withEngineLines<T>(
  env: Env,
  since: string,
  what: string,
  pick: (line: string) => boolean,
  wait: () => Promise<T>,
): Promise<T> {
  try {
    return await wait();
  } catch (cause) {
    const lines = (await env.engineLogs(since)).split("\n").filter(pick);
    const first = lines.length > 0 ? `; the first: ${lines[0]}` : "";
    const message = cause instanceof Error ? cause.message : String(cause);
    throw new Error(`${message}\n\nthe engine logged ${lines.length} lines ${what} since ${since}${first}`, { cause });
  }
}
