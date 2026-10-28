/**
 * The `since` a spec hands `env.logs`.
 *
 * Rule 18 makes the scoped window a suite-wide idiom, and every window is opened the same way, so
 * the slack below is decided once rather than re-argued at each call site that opens one.
 */
import type { Env } from "../env/index.js";

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
