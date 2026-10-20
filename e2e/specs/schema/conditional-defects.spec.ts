/**
 * The `matches` of every `ConditionalDefect`, against the failure messages it has to recognize and the
 * ones it must leave a real failure, and the engine gate `strikesAsKnown` puts in front of it. No stack.
 */
import { test, expect, type APIResponse } from "@playwright/test";
import { firingHistory, MOCK_BYPASSED, SCHEDULER_FIRES_ON_ANOTHER_DOMAIN } from "../runtime/micro-defects.js";
import { DISCOVERY_RESULT_AFTER_PROGRESS, RESULT_AT_100 } from "../k8s/catalog-defects.js";
import { KILL_NOT_FOUND_UNSPECIFIC, LIVE_EXCHANGE_UNLISTED } from "../global/micro-defects.js";
import { countedAnswer, VALIDATION_READ_BEFORE_ALERTS } from "../global/catalog-defects.js";
import { withHeldAnswer, type HeldCall } from "../../support/held-call.js";
import { strikes, strikesAsKnown } from "../../support/known-defect.js";
import { STRUCK_ANNOTATION } from "../../registry/elements.js";
import { linesOfChain, withEngineLines } from "../../support/logs.js";
import type { Env } from "../../env/index.js";

const TAGS = { tag: ["@infra", "@tier1"] };

/** The message of the failure `assertion` throws, as `strikesAsKnown` reads it. */
function failureOf(assertion: () => void): string {
  try {
    assertion();
  } catch (cause) {
    return String(cause);
  }
  throw new Error("the assertion passed, so there is no failure to read");
}

// A classic regression of the same feature can fail with the same message, and it has to stay red.
test("a micro defect is known only on the micro engine, and the same failure on classic is a real one", TAGS, async () => {
  const unresolved = 'Error: {\n  "code" : "CIP-0101",\n}';
  expect(strikes(MOCK_BYPASSED, "micro", unresolved)).toBe(true);
  expect(strikes(MOCK_BYPASSED, "classic", unresolved)).toBe(false);

  // This project sets no engine kind, so it runs as a classic one: the failure comes back, and the
  // case is still expected to pass. The status is put back before it is asserted, since a case
  // marked to fail would report this assertion's failure as expected.
  const thrown = await strikesAsKnown(MOCK_BYPASSED, async () => {
    throw new Error(unresolved);
  }).catch((cause: unknown) => String(cause));
  const expectedStatus = test.info().expectedStatus;
  test.info().expectedStatus = "passed";
  expect(thrown).toContain("CIP-0101");
  expect(expectedStatus).toBe("passed");
});

test("a strike is recorded beside the expected failure, so reconcile can read the calls before it", TAGS, async () => {
  const race = { title: "a race", engine: "any" as const, matches: (message: string) => message.includes("raced") };
  await strikesAsKnown(race, async () => {
    throw new Error("raced");
  }).catch(() => {});
  // Put back before asserting, for the reason the case above gives.
  const expectedStatus = test.info().expectedStatus;
  test.info().expectedStatus = "passed";
  expect(expectedStatus).toBe("failed");
  expect(test.info().annotations).toContainEqual({ type: STRUCK_ANNOTATION, description: "a race" });
});

test("a mocked service call that reached its real address is the known defect, and an unmatched mock is not", TAGS, () => {
  // The two failures the micro engine produced, as `strikesAsKnown` reads them.
  const unresolved = [
    "Error: {",
    '  "code" : "CIP-0101",',
    '  "reason" : "Chain execution failed due to requesting missing endpoint",',
    "}",
    "",
    "expect(received).toBe(expected) // Object.is equality",
  ].join("\n");
  const egress = "Error: no healthy upstream\n\nexpect(received).toBe(expected) // Object.is equality\n\nExpected: 201\nReceived: 503";
  // A test case run of `http-out`, whose rule read the real endpoint's answer.
  const health = [
    `Error: the case run's outbound call was not mocked: '{"status":"UP","groups":["liveness","readiness"],"components":{"ping":{"status":"UP"}}}' doesn't contain '"mocked":"e2e-s7rodt-runs-mocked"'`,
    "",
    "expect(received).toEqual(expected) // deep equality",
  ].join("\n");
  expect(MOCK_BYPASSED.matches(unresolved)).toBe(true);
  expect(MOCK_BYPASSED.matches(egress)).toBe(true);
  expect(MOCK_BYPASSED.matches(health)).toBe(true);

  // The testing service answers a request no mock matched with 404.
  expect(MOCK_BYPASSED.matches("Error: no mock matched: the element sent another path, query or body. \n\nExpected: 201\nReceived: 404")).toBe(false);
  expect(MOCK_BYPASSED.matches("Error: expect(received).toBe(expected)\n\nExpected: 201\nReceived: 500")).toBe(false);
  // The mock answered, with another body than the rule expects.
  expect(MOCK_BYPASSED.matches(`Error: the case run's outbound call was not mocked: '{"mocked":"other"}' doesn't contain '"mocked":"e2e-s7rodt-runs-mocked"'`)).toBe(false);
});

/** What `withEngineLines` throws when `wait` fails with `failure` and the engine log reads `log`. */
async function engineLinesFailure(
  log: string,
  what: string,
  pick: (line: string) => boolean,
  failure = "sessions of e2e0b001-0000-4000-8000-000000000001\n\nExpected length: 1\nReceived length: 0",
): Promise<string> {
  const env = { engineLogs: async () => log } as unknown as Env;
  const failed = withEngineLines(env, "2026-10-14T06:18:41.203Z", what, pick, async () => {
    throw new Error(failure);
  });
  return String(await failed.catch((cause: unknown) => cause));
}

test("a scheduler chain that stopped firing on its micro domain beside another is the known defect, and one that never fired is not", TAGS, async () => {
  const chain = "dfd387a7-7dcc-4e05-8942-e94fcd7bc936";
  // What `nextFiring` in scheduler.spec.ts throws when no fresh settled session appears.
  const timedOut = "Error: expect(received).toBe(expected) // Object.is equality\n\nExpected: true\nReceived: false\n\nCall Log:\n- Timeout 20000ms exceeded while waiting on the predicate";
  const failure = (earlier: number, others: string[], log = "") =>
    engineLinesFailure(log, `for chain ${chain}`, linesOfChain(chain), `${firingHistory("e2e-x-scheduler-basic", "e2e-x-micro", earlier, others)}\n\n${timedOut}`);

  // CI run 38673581259: the chain had fired on its domain, the brokers-micro domain ran beside it,
  // and the pod logged nothing for the chain during the wait.
  const stopped = await failure(412, ["e2e-x-brokers-micro"]);
  expect(stopped).toContain(`the engine logged 0 lines for chain ${chain}`);
  expect(strikes(SCHEDULER_FIRES_ON_ANOTHER_DOMAIN, "micro", stopped)).toBe(true);
  expect(strikes(SCHEDULER_FIRES_ON_ANOTHER_DOMAIN, "classic", stopped)).toBe(false);

  // The chain never fired on its domain: the scheduler or the route never started.
  expect(SCHEDULER_FIRES_ON_ANOTHER_DOMAIN.matches(await failure(0, ["e2e-x-brokers-micro"]))).toBe(false);
  // No other micro domain shares the Quartz cluster.
  expect(SCHEDULER_FIRES_ON_ANOTHER_DOMAIN.matches(await failure(412, []))).toBe(false);
  // The pod ran the chain, so the firing reached it and something else lost or held the session.
  const ran = await failure(412, ["e2e-x-brokers-micro"], `[INFO ] [chain_id=${chain}] [chain=e2e-x-scheduler-basic] Session started`);
  expect(SCHEDULER_FIRES_ON_ANOTHER_DOMAIN.matches(ran)).toBe(false);
  expect(SCHEDULER_FIRES_ON_ANOTHER_DOMAIN.matches(timedOut)).toBe(false);
});

/** What `withHeldAnswer` throws when `step` fails with `failure` and the held call answered `status` and `body`. */
async function heldAnswerFailure(failure: string, status: number, body: string): Promise<string> {
  const response = { status: () => status, text: async () => body } as unknown as APIResponse;
  const call: HeldCall = { token: "t", answer: Promise.resolve(response) };
  const thrown = await withHeldAnswer(call, async () => {
    throw new Error(failure);
  }).catch((error: unknown) => error);
  return String(thrown);
}

test("an exchange the micro engine never listed while it was held is the known defect, and one that was not held is not", TAGS, async () => {
  const held = '{"element":"long-running","heldMs":12000}';
  const fromCatalog = await heldAnswerFailure("no live exchange for chain c1 was ever listed", 200, held);
  expect(fromCatalog).toContain(`the held call answered 200 ${held}`);
  expect(LIVE_EXCHANGE_UNLISTED.matches(fromCatalog)).toBe(true);
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("the engine never reported an exchange for e2e-x-long-running", 200, held))).toBe(true);

  // The call failed, or answered without holding, so nothing was in flight to list.
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("no live exchange for chain c1 was ever listed", 503, "no healthy upstream"))).toBe(false);
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("no live exchange for chain c1 was ever listed", 200, '{"element":"other"}'))).toBe(false);
  // The exchange was listed, and a later assertion failed.
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("expect(received).toBe(expected)\n\nExpected: true\nReceived: false", 200, held))).toBe(false);
});

test("an unknown deployment the micro engine reports as an unknown exchange is the known defect, and another 404 is not", TAGS, () => {
  const micro = `Error: the engine answered {"serviceName":"Engine","errorMessage":"No live exchange found for deployment id no-such-deployment"}\n\nexpect(received).toContain(expected) // indexOf`;
  expect(KILL_NOT_FOUND_UNSPECIFIC.matches(micro)).toBe(true);
  expect(KILL_NOT_FOUND_UNSPECIFIC.matches("Error: the engine answered Not Found\n\nexpect(received).toContain(expected) // indexOf")).toBe(false);
});

/** What `strikesAsKnown` reads when the discovery case's result assertion fails on `read`. */
function resultFailure(read: unknown): string {
  return failureOf(() => expect(read, RESULT_AT_100).toBeDefined());
}

test("an empty discovery result at progress 100 is the known defect on either engine, and a failed read is not", TAGS, () => {
  const empty = resultFailure(undefined);
  expect(strikes(DISCOVERY_RESULT_AFTER_PROGRESS, "classic", empty)).toBe(true);
  expect(strikes(DISCOVERY_RESULT_AFTER_PROGRESS, "micro", empty)).toBe(true);

  // The catalog refused the read, so the result was never empty.
  const refused = "Error: GET http://localhost:8090/v1/systems/discovery/result answered 500: Error in Autodiscovery service: timeout";
  expect(strikes(DISCOVERY_RESULT_AFTER_PROGRESS, "classic", refused)).toBe(false);
  // Another assertion that received nothing is not the discovery result.
  expect(strikes(DISCOVERY_RESULT_AFTER_PROGRESS, "classic", "Error: expect(received).toBeDefined()\n\nReceived: undefined")).toBe(false);
});

/** What `strikesAsKnown` reads when the diagnostic round-trip's findings assertion fails on `found`. */
function findingsFailure(found: number, alertsCount: number, entities: number): string {
  return failureOf(() => expect(new Array(found), `the run did not flag the script this case added ${countedAnswer(alertsCount, entities)}`).toHaveLength(1));
}

test("a validation read whose count and entities disagree is the known defect on either engine, and a consistent read is not", TAGS, () => {
  // CI run 38680913931: the count already held the new alert, the entities did not.
  const straddled = findingsFailure(0, 207, 206);
  expect(strikes(VALIDATION_READ_BEFORE_ALERTS, "classic", straddled)).toBe(true);
  expect(strikes(VALIDATION_READ_BEFORE_ALERTS, "micro", straddled)).toBe(true);

  // A count that differs only by a digit the other number lacks still differs.
  expect(strikes(VALIDATION_READ_BEFORE_ALERTS, "classic", findingsFailure(0, 20, 206))).toBe(true);
  expect(strikes(VALIDATION_READ_BEFORE_ALERTS, "classic", findingsFailure(0, 206, 2060))).toBe(true);

  // The answer agrees with itself, so the rule is what missed the script.
  expect(strikes(VALIDATION_READ_BEFORE_ALERTS, "classic", findingsFailure(0, 206, 206))).toBe(false);
  expect(strikes(VALIDATION_READ_BEFORE_ALERTS, "classic", "Error: expect(received).toHaveLength(expected)\n\nExpected length: 1\nReceived length: 0")).toBe(false);
});
