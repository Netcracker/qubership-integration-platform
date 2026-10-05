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
import { UUID, UUID_TEXT } from "./absent.js";
import { trace, type RecordedSession } from "./sessions.js";

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
 * description: `test.fail(engineKind === "micro", MICRO_STEP_NAMES.title)`.
 *
 * A body cannot narrow such a case the way the functions above do, because the assertion that meets
 * the defect differs from case to case. The `knownDefects` fixture narrows it after the body instead:
 * every error the case raised has to be one that `matches` accepts.
 */
export interface MicroDefect {
  title: string;
  matches(message: string): boolean;
}

const ADDED_UUID_LINE = new RegExp(`^\\+\\s+"${UUID_TEXT}",?$`);
const REMOVED_NAME_LINE = /^-\s+"[^"]+",?$/;

/** The opening of the report `settleMicroDefects` fails a pinned case with. */
const RENAMED_STEPS = "the micro engine named these steps by a UUID in the session:";

/**
 * Whether a `toEqual` diff over step names changes nothing but names into UUIDs: as many removed
 * lines as added ones, each removed line a name, and each added line a UUID.
 *
 * A step the micro engine dropped, added, reordered, or named differently leaves a line that fails
 * one of those conditions.
 */
function onlyUuidsForNames(message: string): boolean {
  const lines = message.split("\n");
  const expected = lines.map((line) => /^- Expected\s+-\s+(\d+)$/.exec(line.trim())).find(Boolean);
  const received = lines.map((line) => /^\+ Received\s+\+\s+(\d+)$/.exec(line.trim())).find(Boolean);
  if (!expected || !received || expected[1] !== received[1]) return false;
  const body = lines.filter((line) => !/^[-+] (Expected|Received)\s/.test(line.trim()));
  const removed = body.filter((line) => line.startsWith("-"));
  const added = body.filter((line) => line.startsWith("+"));
  const count = Number(expected[1]);
  return (
    count > 0 &&
    removed.length === count &&
    added.length === count &&
    removed.every((line) => REMOVED_NAME_LINE.test(line)) &&
    added.every((line) => ADDED_UUID_LINE.test(line))
  );
}

/** docs/product-defects.md, "The micro engine names a wrapped step by a UUID in the session". */
export const MICRO_STEP_NAMES: MicroDefect = {
  title: "the micro engine names a wrapped step by a UUID in the session (docs/product-defects.md)",
  matches: (message) =>
    message.replace(/^Error: /, "").startsWith(RENAMED_STEPS) || onlyUuidsForNames(message),
};

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
export const MICRO_CONTAINER_PARENTS: MicroDefect = {
  title: "the micro engine records a step inside a container with no parent (docs/product-defects.md)",
  matches: (message) => message.replace(/^Error: /, "").startsWith(MISPLACED_BRANCH_STEPS),
};

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

const MICRO_DEFECTS = [MICRO_STEP_NAMES, MICRO_XSLT, MICRO_CONTAINER_PARENTS];

function pinnedWith(testInfo: TestInfo, defect: MicroDefect): boolean {
  return testInfo.annotations.some((each) => each.type === "fail" && each.description === defect.title);
}

/** A step the micro engine named by a UUID, and the name its chain element carries. */
export interface RenamedStep {
  uuid: string;
  name: string;
}

/**
 * Gives each step of `session` that the micro engine named by a UUID the name of its chain
 * element, and appends each rename to `renamed` for `settleMicroDefects`.
 *
 * Only in a case pinned with `MICRO_STEP_NAMES`, so the rest of that case asserts on the names the
 * classic engine records, and the case still fails on the defect once its body has passed. A case
 * without the pin reads the session as the engine wrote it.
 */
export function nameMicroSteps(
  testInfo: TestInfo,
  session: RecordedSession,
  names: ReadonlyMap<string, string>,
  renamed: RenamedStep[],
): void {
  if (!pinnedWith(testInfo, MICRO_STEP_NAMES)) return;
  for (const step of trace(session)) {
    const name = step.chainElementId === null ? undefined : names.get(step.chainElementId);
    if (name !== undefined && UUID.test(step.elementName)) {
      renamed.push({ uuid: step.elementName, name });
      step.elementName = name;
    }
  }
}

/**
 * Settles a case pinned with a `MicroDefect` once its body has run.
 *
 * A case whose steps `nameMicroSteps` renamed, and whose body raised nothing, fails here on the
 * step-name defect. Otherwise an error that no pinned defect `matches` turns the case back into a
 * real failure. A case that passes needs nothing: Playwright already reports an expected failure
 * that passed.
 */
export function settleMicroDefects(testInfo: TestInfo, renamed: readonly RenamedStep[]): void {
  if (testInfo.expectedStatus !== "failed") return;
  if (renamed.length > 0 && testInfo.errors.length === 0 && pinnedWith(testInfo, MICRO_STEP_NAMES)) {
    const unique = [...new Map(renamed.map((each) => [each.uuid, each])).values()];
    throw new Error(
      `${RENAMED_STEPS} ${unique.map((each) => `"${each.name}" as ${each.uuid}`).join(", ")}`,
    );
  }
  narrowToMicroDefects(testInfo);
}

/**
 * Turns a case pinned with a `MicroDefect` back into a real failure when an error it raised is not
 * one the defect `matches`.
 *
 * A case whose body reached a `test.fail()` of its own narrows itself, through `notTheKnownDefect`,
 * because its errors from there on belong to that pin.
 */
function narrowToMicroDefects(testInfo: TestInfo): void {
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
