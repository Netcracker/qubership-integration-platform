/**
 * The `matches` of every `ConditionalDefect`, against the failure messages it has to recognize and the
 * ones it must leave a real failure, and the engine gate `strikesAsKnown` puts in front of it. No stack.
 */
import { test, expect, type APIResponse } from "@playwright/test";
import { firingHistory, SCHEDULER_FIRES_ON_ANOTHER_DOMAIN } from "../runtime/micro-defects.js";
import { withHeldAnswer, type HeldCall } from "../../support/held-call.js";
import { strikes, strikesAsKnown } from "../../support/known-defect.js";
import { STRUCK_ANNOTATION } from "../../registry/elements.js";
import { linesOfChain, withEngineLines } from "../../support/logs.js";
import type { Env } from "../../env/index.js";

test.describe("conditional defects", { tag: ["@harness"] }, () => {
  // A classic regression of the same feature can fail with the same message, and it has to stay red.
  test("a micro defect strikes on micro and stays red on classic", async () => {
    const unresolved = 'Error: {\n  "code" : "CIP-0101",\n}';
    const onMicro = { title: "a micro defect", engine: "micro" as const, matches: (message: string) => message.includes("CIP-0101") };
    expect(strikes(onMicro, "micro", unresolved)).toBe(true);
    expect(strikes(onMicro, "classic", unresolved)).toBe(false);

    // This project sets no engine kind, so it runs as a classic one: the failure comes back, and the
    // case is still expected to pass. The status is put back before it is asserted, since a case
    // marked to fail would report this assertion's failure as expected.
    const thrown = await strikesAsKnown(onMicro, async () => {
      throw new Error(unresolved);
    }).catch((cause: unknown) => String(cause));
    const expectedStatus = test.info().expectedStatus;
    test.info().expectedStatus = "passed";
    expect(thrown).toContain("CIP-0101");
    expect(expectedStatus).toBe("passed");
  });

  // Reconcile reads the annotation to count the calls made before the strike.

  test("a strike is recorded beside the expected failure", async () => {
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

  // A chain that never fired, ran on the pod, or had no other domain beside it does not.

  test("a scheduler chain that stopped beside another domain strikes", async () => {
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

  test("a failed step under a held call names the held answer", async () => {
    const held = '{"element":"long-running","heldMs":12000}';
    expect(await heldAnswerFailure("no live exchange for chain c1 was ever listed", 200, held)).toContain(`the held call answered 200 ${held}`);
  });
});
