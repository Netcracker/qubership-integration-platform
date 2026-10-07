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
 * through `onlyTheKnownStatus`. A micro-engine defect that many cases share is narrowed after the
 * body instead, by `settleMicroDefects`.
 */
import { test } from "@playwright/test";
import type { APIResponse, TestInfo } from "@playwright/test";

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
 * A micro-engine difference that many cases share, pinned by its `title` as the `test.fail()`
 * description: `test.fail(engineKind === "micro", MICRO_XSLT.title)`.
 *
 * A body cannot narrow such a case the way the functions above do, because the assertion that meets
 * the defect differs from case to case. The `knownDefects` fixture narrows it after the body instead:
 * every error the case raised has to be one that `matches` accepts.
 */
export interface MicroDefect {
  title: string;
  matches(message: string): boolean;
}

/** docs/product-defects.md, "The micro engine has no xslt component". */
export const MICRO_XSLT: MicroDefect = {
  title: "the micro engine has no xslt component (docs/product-defects.md)",
  // The case puts the engine's log line for the endpoint it could not resolve in the message.
  matches: (message) =>
    /No endpoint could be found for: xslt:[\s\S]*Expected: 200\s+Received: 500/.test(message),
};

/** The opening of the message the split-async case fails with when a branch step has the wrong parent. */
export const MISPLACED_BRANCH_STEPS = "a step of an async branch is recorded under another parent:";

/** docs/product-defects.md, "The micro engine records a step inside a container with no parent". */
export const MICRO_CONTAINER_PARENTS: ConditionalDefect = {
  title: "the micro engine records a step inside a container with no parent (docs/product-defects.md)",
  matches: (message) => message.replace(/^Error: /, "").startsWith(MISPLACED_BRANCH_STEPS),
};

/**
 * A defect a case meets only on some runs, because it depends on how much data the stack holds or on
 * how threads interleave, so the case cannot carry a plain `test.fail()`: on a run that misses the
 * defect it passes, and the annotation would turn that pass red.
 */
export interface ConditionalDefect {
  title: string;
  matches(message: string): boolean;
}

/**
 * #985: behind an Istio sidecar, a session search that returns many sessions answers 400 over an
 * OpenSearch 502, because the collapsed search sends more response headers than Envoy accepts.
 */
export const SESSION_SEARCH_HEADER_LIMIT: ConditionalDefect = {
  title: "a session search returning many sessions fails behind an Istio sidecar (#985)",
  matches: (message) => message.includes("502 Bad Gateway") && message.includes("reset reason: protocol error"),
};

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

const MICRO_DEFECTS = [MICRO_XSLT];

function pinnedWith(testInfo: TestInfo, defect: MicroDefect): boolean {
  return testInfo.annotations.some((each) => each.type === "fail" && each.description === defect.title);
}

/**
 * Settles a case pinned with a `MicroDefect` once its body has run: an error that no pinned defect
 * `matches` turns the case back into a real failure. A case that passes needs nothing: Playwright
 * already reports an expected failure that passed.
 *
 * A case whose body reached a `test.fail()` of its own narrows itself, through `notTheKnownDefect`,
 * because its errors from there on belong to that pin.
 */
export function settleMicroDefects(testInfo: TestInfo): void {
  if (testInfo.expectedStatus !== "failed") return;
  const pinned = MICRO_DEFECTS.filter((defect) => pinnedWith(testInfo, defect));
  if (pinned.length === 0) return;
  const titles = MICRO_DEFECTS.map((defect) => defect.title);
  if (testInfo.annotations.some((each) => each.type === "fail" && !titles.includes(each.description ?? ""))) {
    return;
  }
  const foreign = testInfo.errors
    .map((error) => stripAnsi(error.message ?? error.value ?? ""))
    .filter((message) => !pinned.some((defect) => defect.matches(message)));
  if (foreign.length > 0) testInfo.expectedStatus = "passed";
}

function stripAnsi(text: string): string {
  // eslint-disable-next-line no-control-regex
  return text.replace(/\x1b\[[0-9;]*m/g, "");
}
