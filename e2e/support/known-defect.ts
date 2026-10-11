/**
 * Narrowing a `test.fail()` case to the one divergence it carries.
 *
 * `test.fail()` inverts the verdict, and it accepts **any** failure. A case pinned that way over
 * `expect(response.status()).toBe(406)` is satisfied just as readily by a service that never
 * answered, so the annotation reports a stack that is down as green — which is the opposite of what
 * rule 13 asks the annotation to buy. Clearing `expectedStatus` turns every outcome but the known
 * defect back into a red test.
 *
 * "Never answered" has two shapes and both are closed here. A service that answers the wrong status
 * is caught by comparing the status; a service that answers nothing at all never produces a response
 * object, because `APIRequestContext.fetch` — which every transport in `support/` awaits — **throws**
 * on a connection failure. That is why `onlyTheKnownStatus` takes the unawaited call rather than a
 * status: a helper handed `response.status()` is reached only once the transport has already
 * succeeded, and the throw it was meant to catch has already been reported as expected.
 *
 * Every `test.fail()` case in the suite narrows itself through this module: the ones that read more
 * than a status call `notTheKnownDefect` directly and run each step the defect cannot explain through
 * `outsideTheDefect`, and the rest — a defect that is one status and a fix that is another — go
 * through `onlyTheKnownStatus`.
 */
import { test } from "@playwright/test";
import type { APIResponse } from "@playwright/test";

/**
 * Fails the running `test.fail()` case for real, because what it saw is not the defect it pins.
 *
 * `never` rather than `void`, so a call inside an `if` narrows the value the caller went on to
 * read, such as a response that may be `null`.
 */
export function notTheKnownDefect(why: string): never {
  test.info().expectedStatus = "passed";
  throw new Error(why);
}

/** Runs a step of a `test.fail()` case, and fails the case for real if the step throws. */
export async function outsideTheDefect<T>(what: string, step: () => Promise<T>): Promise<T> {
  return await step().catch((cause: unknown) => notTheKnownDefect(`${what} failed: ${String(cause)}`));
}

/**
 * The narrowing for the common shape: a defect that answers one status and a fix that answers
 * another.
 *
 * Takes the call rather than its result, so the transport throw is inside the narrowing rather than
 * ahead of it. Anything but the two statuses is a failure this annotation does not carry — a service
 * that is down, a 500 raised on a path the case never meant to exercise — and `test.fail()` would
 * report every one of them as "expected". The body is read only to name what came back, and the
 * status is answered so a caller can go on asserting on it.
 */
export async function onlyTheKnownStatus(
  answering: Promise<APIResponse>,
  expected: { defect: number; fixed: number; what: string },
): Promise<number> {
  const response = await outsideTheDefect(expected.what, () => answering);
  const actual = response.status();
  if (actual !== expected.defect && actual !== expected.fixed) {
    const body = (await response.text().catch(() => "<unreadable>")).slice(0, 400);
    notTheKnownDefect(
      `${expected.what} answered ${actual}: ${body}. ` +
        `The defect answers ${expected.defect} and the fix owes ${expected.fixed}; this ` +
        `test.fail() carries neither, so it is a new failure rather than the divergence it pins`,
    );
  }
  return actual;
}

/**
 * A defect a case meets only on some stacks, because it depends on how much data the stack holds, so
 * the case cannot carry a plain `test.fail()`: on a small stack it passes, and the annotation would
 * turn that pass red.
 */
export interface ConditionalDefect {
  title: string;
  matches(message: string): boolean;
}

/**
 * Runs `step`, and marks the running case an expected failure only when `step` throws `defect`. Any
 * other failure stays a real one, and a step that succeeds leaves the case an ordinary passing case.
 */
export async function strikesAsKnown<T>(defect: ConditionalDefect, step: () => Promise<T>): Promise<T> {
  try {
    return await step();
  } catch (cause) {
    if (defect.matches(String(cause))) test.fail(true, defect.title);
    throw cause;
  }
}
