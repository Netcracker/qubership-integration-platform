/**
 * `samplesUntil`, which the micro redeploy case reads every call of, over a counter instead of a stack.
 *
 * Shown failing: four edits to `samplesUntil` each turned a case red. They ignored `done`, ignored
 * the deadline, returned only the last reading, and dropped the first.
 */
import { test, expect } from "@playwright/test";
import { samplesUntil } from "../../support/poll.js";

/** A reader that answers 1, 2, 3, and so on, one number per call. */
function counter(): () => Promise<number> {
  let next = 0;
  return async () => ++next;
}

test.describe("samplesUntil", { tag: ["@harness"] }, () => {
  test("answers every reading up to the first that is done", async () => {
    expect(await samplesUntil(counter(), (value) => value === 3, 5_000, 1)).toEqual([1, 2, 3]);
  });

  test("answers one reading when the first is done", async () => {
    expect(await samplesUntil(counter(), () => true, 5_000, 1)).toEqual([1]);
  });

  test("answers what it read once the timeout runs out", async () => {
    const samples = await samplesUntil(counter(), () => false, 200, 50);
    // 200 ms at one reading per 50 ms is five readings, or six when a timer fires a millisecond early.
    expect(samples.length).toBeGreaterThan(1);
    expect(samples.length).toBeLessThanOrEqual(6);
    expect(samples).toEqual(samples.map((_, index) => index + 1));
  });
});
