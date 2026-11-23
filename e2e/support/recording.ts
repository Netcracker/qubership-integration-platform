import { expect, test } from "@playwright/test";
import { readUntil } from "./poll.js";
import { callToken } from "./run-token.js";
import { SESSION_TIMEOUT } from "./sessions.js";

/**
 * How many calls right after a deploy may record no session before `waitForRecording` fails.
 *
 * A chain deployed in the same engine batch as another can lose the session of its first call, and
 * only that one. Two leave one call of slack;
 * above that, a loss that grows shows up as a red case instead of slower warm-ups.
 */
export const UNRECORDED_WARM_UP_LIMIT = 2;

/** A warm-up call carrying `marker`, and the check whether the call's session arrived. */
export interface WarmUp {
  send(marker: string): Promise<void>;
  recorded(marker: string): Promise<boolean>;
}

/**
 * Returns once a warm-up call records a session, counting the calls before it that recorded none.
 *
 * A call right after a deploy can record nothing; see `UNRECORDED_WARM_UP_LIMIT`. Warm-up calls
 * carry their own markers, so they reach nothing a case asserts.
 */
export async function waitForFirstRecording(what: string, warmUp: WarmUp, timeout: number): Promise<void> {
  const markers: string[] = [];
  let first = -1;
  await expect
    .poll(
      async () => {
        const marker = callToken("warm-up");
        markers.push(marker);
        await warmUp.send(marker);
        for (const [index, each] of markers.entries()) {
          if (await warmUp.recorded(each)) {
            first = index;
            return true;
          }
        }
        return false;
      },
      { timeout, intervals: [1_000], message: `${what} records no session` },
    )
    .toBe(true);

  // An earlier call may only be indexed later, so it counts as unrecorded after the lookup budget.
  let pending = markers.slice(0, first);
  const unrecorded = await readUntil(
    async () => {
      const still: string[] = [];
      for (const each of pending) if (!(await warmUp.recorded(each))) still.push(each);
      return (pending = still);
    },
    (still) => still.length === 0,
    SESSION_TIMEOUT,
    1_000,
  );
  if (unrecorded.length > 0) {
    const description = `${what}: ${unrecorded.length} of the first ${first + 1} warm-up call(s) recorded no session`;
    console.log(`[warm-up] ${description}`);
    test.info().annotations.push({ type: "unrecorded warm-up", description });
  }
  expect(
    unrecorded.length,
    `${what}: more calls after the deploy recorded no session than the known loss accounts for ` +
      `(a chain deployed in the same engine batch as another can lose the session of its first call)`,
  ).toBeLessThanOrEqual(UNRECORDED_WARM_UP_LIMIT);
}
