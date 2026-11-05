/**
 * `samplesUntil`, which the micro redeploy case reads every call of, over a counter instead of a stack.
 *
 * Shown failing: four edits to `samplesUntil` each turned a case red. They ignored `done`, ignored
 * the deadline, returned only the last reading, and dropped the first.
 * Shown failing: advancing the clock past the deadline during the first read broke the old sample-count assertion.
 */
import { test, expect } from "@playwright/test";
import { mock } from "node:test";
import { samplesUntil } from "../../support/poll.js";

/** A reader that answers 1, 2, 3, and so on, one number per call. */
function counter(): () => Promise<number> {
  let next = 0;
  return async () => ++next;
}

test.describe("samplesUntil", () => {
  test.afterEach(() => mock.restoreAll());

  test("answers every reading up to the first that is done", async () => {
    expect(await samplesUntil(counter(), (value) => value === 3, 5_000, 1)).toEqual([1, 2, 3]);
  });

  test("answers one reading when the first is done", async () => {
    expect(await samplesUntil(counter(), () => true, 5_000, 1)).toEqual([1]);
  });

  test("answers what it read once the timeout runs out", async () => {
    let now = 0;
    let readings = 0;
    mock.method(Date, "now", () => now);
    const samples = await samplesUntil(async () => {
      now += 100;
      expect(++readings, "a read started after the deadline").toBeLessThanOrEqual(2);
      return readings;
    }, () => false, 200, 0);

    expect(samples).toEqual([1, 2]);
  });

  test("returns the first read when it exhausts the timeout", async () => {
    let now = 0;
    let readings = 0;
    mock.method(Date, "now", () => now);
    const samples = await samplesUntil(async () => {
      now += 250;
      expect(++readings, "a read started after the deadline").toBe(1);
      return readings;
    }, () => false, 200, 0);

    expect(samples).toEqual([1]);
  });
});
