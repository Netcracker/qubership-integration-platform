/**
 * The `matches` of every `ConditionalDefect`, against the failure messages it has to recognize and the
 * ones it must leave a real failure, and the engine gate `strikesAsKnown` puts in front of it. No stack.
 */
import { test, expect, type APIResponse } from "@playwright/test";
import { withHeldAnswer, type HeldCall } from "../../support/held-call.js";
import { strikes, strikesAsKnown } from "../../support/known-defect.js";
import { STRUCK_ANNOTATION } from "../../registry/elements.js";

test.describe("conditional defects", () => {
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
