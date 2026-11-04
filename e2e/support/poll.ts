/**
 * Waits that are not an assertion.
 *
 * Rule 7 routes timing through `expect.poll` or `expect.toPass`, which throw once the budget runs
 * out. A `test.fail()` pin needs the last reading instead, to tell the defect from another failure,
 * and a breaker case needs its window to pass. A redeploy case reads every call it sent, not only the
 * last. All three go through here rather than a loop of their own.
 */

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** `read` until `done` holds or `timeout` runs out, answering the last reading either way. */
export async function readUntil<T>(
  read: () => Promise<T>,
  done: (value: T) => boolean,
  timeout: number,
  interval = 500,
): Promise<T> {
  return (await samplesUntil(read, done, timeout, interval)).at(-1) as T;
}

/** `read` until `done` holds or `timeout` runs out, answering every reading in order either way. */
export async function samplesUntil<T>(
  read: () => Promise<T>,
  done: (value: T) => boolean,
  timeout: number,
  interval = 500,
): Promise<T[]> {
  const deadline = Date.now() + timeout;
  const samples = [await read()];
  while (!done(samples.at(-1) as T) && Date.now() < deadline) {
    await sleep(interval);
    samples.push(await read());
  }
  return samples;
}
