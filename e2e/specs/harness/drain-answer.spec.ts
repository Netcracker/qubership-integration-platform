/**
 * `isDrainAnswer`, which the redeploy case in `specs/api/deployments.spec.ts` reads every sample
 * through, over recorded answers instead of a redeploy.
 *
 * Shown failing: comparing the echo against another body, and accepting an echo that carries the
 * marker, each turned a case red.
 */
import { test, expect } from "@playwright/test";
import { isDrainAnswer, type RouteAnswer } from "../../support/drain-answer.js";

const SENT = JSON.stringify({ ping: 1 });

function answer(fields: Partial<RouteAnswer>): RouteAnswer {
  return { status: 500, marker: null, body: "", sent: SENT, ...fields };
}

test.describe("isDrainAnswer", () => {
  test("accepts 500 CIP-0001", () => {
    expect(isDrainAnswer(answer({ body: JSON.stringify({ code: "CIP-0001" }) }))).toBe(true);
  });

  test("accepts 500 that echoes the request", () => {
    // Run 39771885404: the stopping context rejected the exchange before the chain ran.
    expect(isDrainAnswer(answer({ body: '{"ping":1}' }))).toBe(true);
  });

  test("refuses every other answer", () => {
    expect(isDrainAnswer(answer({ body: '{"ping":1}', marker: "before" })), "an echo from the chain").toBe(false);
    expect(isDrainAnswer(answer({ body: '{"ping":2}' })), "a body the sample did not send").toBe(false);
    expect(isDrainAnswer(answer({ body: JSON.stringify({ code: "CIP-0002" }) })), "another code").toBe(false);
    expect(isDrainAnswer(answer({ status: 404, body: '{"ping":1}' })), "a 404").toBe(false);
    expect(isDrainAnswer(answer({ status: 502, body: JSON.stringify({ code: "CIP-0001" }) })), "a 502").toBe(false);
  });
});
