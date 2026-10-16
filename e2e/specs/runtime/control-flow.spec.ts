/**
 * The control flow families past tier 1: which child of a condition or a try-catch-finally runs,
 * and when a circuit breaker stops calling its main branch.
 *
 * None of `condition`, `try-catch-finally-2` or `circuit-breaker-2` declares a discriminator; they
 * branch on data, so their cases are behavioral and close element rows. The one axis here is
 * `slidingWindowType` on `circuit-breaker-configuration-2`.
 *
 * Every case asserts the trace, because every branch answers the same status: a narrow value taken
 * by the broad `if`, or a checked exception swallowed by the runtime catch, changes only the steps.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain, type SeedChain } from "../../support/corpus.js";
import { sleep } from "../../support/poll.js";
import { callChain, element, elementNames, HTTP_TRIGGER_STEPS, type ExecutionStatus, type Sessions } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import type { APIRequestContext } from "@playwright/test";
import type { Env } from "../../env/index.js";

interface ConditionCase {
  header?: string;
  branch: string;
  steps: string[];
}

// The broad `if` comes first in the fixture and last by priority.
const CONDITIONS: ConditionCase[] = [
  { header: "narrow", branch: "narrow", steps: ["Narrow If", "Narrow Branch"] },
  { header: "other", branch: "broad", steps: ["Broad If", "Broad Branch"] },
  { branch: "else", steps: ["Else", "Else Branch"] },
];

for (const condition of CONDITIONS) {
  test(`condition: e2e-branch ${condition.header ?? "absent"} runs the ${condition.branch} child`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("condition");
    covers(condition.branch === "else" ? "else" : "if");

    const chain = seedChain(readCorpusState(), "condition-branches");
    const call = await callChain(request, env.chainUrl(chain.contextPath), {
      headers: condition.header ? { "e2e-branch": condition.header } : {},
      data: { ping: "condition" },
    });
    expect(call.response.status()).toBe(200);
    expect(JSON.parse(await call.response.text())).toEqual({ branch: condition.branch });
    expect(elementNames(await sessions.byExternalId(call.token, { elements: 5 }))).toEqual([
      ...HTTP_TRIGGER_STEPS,
      "Condition",
      ...condition.steps,
    ]);
  });
}

interface TryCase {
  thrown: string;
  status: number;
  body?: unknown;
  executionStatus: ExecutionStatus;
  /** The steps between the try script and `Finally`: the catch that ran, if any. */
  caught: string[];
}

// The runtime catch comes first in the fixture and last by priority; an IOException matches neither.
const TRIES: TryCase[] = [
  { thrown: "none", status: 200, body: { try: "completed" }, executionStatus: "COMPLETED_NORMALLY", caught: [] },
  { thrown: "argument", status: 200, body: { caught: "argument" }, executionStatus: "COMPLETED_WITH_WARNINGS", caught: ["Argument Catch", "Argument Catch Branch"] },
  { thrown: "state", status: 200, body: { caught: "runtime" }, executionStatus: "COMPLETED_WITH_WARNINGS", caught: ["Runtime Catch", "Runtime Catch Branch"] },
  { thrown: "checked", status: 500, executionStatus: "COMPLETED_WITH_ERRORS", caught: [] },
];

for (const each of TRIES) {
  test(`try-catch-finally: e2e-throw ${each.thrown} runs ${each.caught[0] ?? "no catch"} and the finally`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("try-catch-finally-2");
    covers("try-2");
    covers("finally-2");
    if (each.caught.length > 0) covers("catch-2");

    const chain = seedChain(readCorpusState(), "try-catch-finally-branches");
    const call = await callChain(request, env.chainUrl(chain.contextPath), {
      headers: { "e2e-throw": each.thrown },
      data: { ping: "try" },
    });
    expect(call.response.status()).toBe(each.status);
    if (each.body) {
      expect(JSON.parse(await call.response.text())).toEqual(each.body);
      expect(call.response.headers()["e2e-finally"]).toBe("ran");
    } else {
      // The default failure handler answers the error; only the trace shows the finally ran.
      expect(JSON.parse(await call.response.text())).toMatchObject({ code: "QIP-0001" });
    }

    const session = await sessions.byExternalId(call.token, { elements: 7 + each.caught.length });
    expect(session.executionStatus).toBe(each.executionStatus);
    expect(elementNames(session)).toEqual([
      ...HTTP_TRIGGER_STEPS,
      "Try-Catch-Finally",
      "Try",
      "Try Script",
      ...each.caught,
      "Finally",
      "Finally Branch",
    ]);
    expect(element(session, "Try Script")?.executionStatus).toBe(each.thrown === "none" ? "COMPLETED_NORMALLY" : "COMPLETED_WITH_ERRORS");
    expect(element(session, "Finally Branch")?.executionStatus).toBe("COMPLETED_NORMALLY");
  });
}

const BREAKER = ["Circuit Breaker"];
/** `Main branch` and `On fallback` are the template's step ids, not the child elements' names. */
const MAIN = [...BREAKER, "Main branch", "Main Script"];
const FAILED_OVER = [...MAIN, "On fallback", "Fallback Script"];
const SHORT_CIRCUITED = [...BREAKER, "On fallback", "Fallback Script"];

interface BreakerCall {
  token: string;
  branch: "main" | "fallback";
  steps: string[];
}

/**
 * One call through the breaker, asserting the branch it answered. Its steps are asserted later by
 * `assertTraces`: a session lookup between calls would stretch the timing the window depends on.
 */
async function breakerCall(request: APIRequestContext, env: Env, chain: SeedChain, fail: boolean, branch: BreakerCall["branch"], steps: string[]): Promise<BreakerCall> {
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "e2e-fail": String(fail) },
    data: { ping: "breaker" },
  });
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text()), `the call expected to take ${steps.join(" > ")}`).toEqual({ branch });
  return { token: call.token, branch, steps };
}

async function assertTraces(sessions: Sessions, calls: BreakerCall[]): Promise<void> {
  for (const call of calls) {
    const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + call.steps.length });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, ...call.steps]);
  }
}

/** Both fixtures' `slidingWindowSize`: five calls for one, five seconds for the other. */
const WINDOW_SECONDS = 5;
/** Both fixtures' `waitDurationInOpenState`. */
const OPEN_SECONDS = 10;

/**
 * Calls the main branch until it answers, which closes a breaker an earlier run left open: after
 * `waitDurationInOpenState` the breaker lets one call through, and its success closes it. Either
 * way the window then holds at most that one successful call.
 */
async function closeBreaker(request: APIRequestContext, env: Env, chain: SeedChain): Promise<void> {
  await expect
    .poll(async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { headers: { "e2e-fail": "false" }, data: { ping: "close" } });
      return (JSON.parse(await call.response.text()) as { branch?: string }).branch;
    }, { timeout: (OPEN_SECONDS + 10) * 1_000, intervals: [1_000], message: `${chain.name} stayed open` })
    .toBe("main");
}

// The two fixtures differ in `slidingWindowType` alone: open at 60% over at least two calls, and
// stay open for ten seconds. Each case closes its breaker first, so a rerun against a kept corpus
// starts from the same state as a fresh seed.
test("slidingWindowType=COUNT_BASED: failures far apart still open the breaker and short-circuit the next call", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  covers("circuit-breaker-2");
  covers("circuit-breaker-configuration-2");
  covers("circuit-breaker-configuration-2", "slidingWindowType", "COUNT_BASED");
  covers("on-fallback-2");

  const chain = seedChain(readCorpusState(), "circuit-breaker-count-based");
  await closeBreaker(request, env, chain);
  const first = await breakerCall(request, env, chain, true, "fallback", FAILED_OVER);
  await sleep((WINDOW_SECONDS + 2) * 1_000);
  // The count window still holds the first failure: two of at most three calls failed.
  const second = await breakerCall(request, env, chain, true, "fallback", FAILED_OVER);
  // The main branch is not called at all: its steps are missing, not failed.
  const probe = await breakerCall(request, env, chain, false, "fallback", SHORT_CIRCUITED);
  await assertTraces(sessions, [first, second, probe]);
});

test("slidingWindowType=TIME_BASED: a failure that left the window does not count toward opening the breaker", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  covers("circuit-breaker-configuration-2", "slidingWindowType", "TIME_BASED");

  const chain = seedChain(readCorpusState(), "circuit-breaker-time-based");
  await closeBreaker(request, env, chain);
  await sleep((WINDOW_SECONDS + 2) * 1_000);
  const first = await breakerCall(request, env, chain, true, "fallback", FAILED_OVER);
  await sleep((WINDOW_SECONDS + 2) * 1_000);
  // From here on the calls go back to back, inside one window.
  const second = await breakerCall(request, env, chain, true, "fallback", FAILED_OVER);
  // Where COUNT_BASED is already open: one call in the window is under the minimum of two.
  const probe = await breakerCall(request, env, chain, false, "main", MAIN);
  // Two failures of three in the window reach 60%.
  const third = await breakerCall(request, env, chain, true, "fallback", FAILED_OVER);
  const shortCircuited = await breakerCall(request, env, chain, false, "fallback", SHORT_CIRCUITED);
  await assertTraces(sessions, [first, second, probe, third, shortCircuited]);
});
