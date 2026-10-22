/**
 * Outbound calls that fail on the first try: a service call that retries a 500, and an HTTP sender
 * whose timeout ends a call the target holds open.
 *
 * Both chains call another seeded chain on the engine's own port. A call carrying the correlation
 * header is a test-case run, and the engine sends its outbound calls to the testing service's mocks,
 * so these calls carry none. Each session is found among the chain's new sessions and tied to its
 * call by the context id or the hold header the trigger received.
 *
 * - `service-call-retry` calls `retry-target`, which counts its calls under the `e2e-context-id`
 *   header in the context service and throws on the first two, so the trigger answers 500. With
 *   `retryCount: 2` the third attempt answers 200. The case fails with `retryCount: 0`: one attempt,
 *   and the caller answers 500.
 * - `sender-response-timeout` calls `long-running` with `connectTimeout: 1000`, which the template
 *   also sets as `soTimeout` and `responseTimeout`, and the call asks for a 5000 ms hold. The case
 *   fails once `connectTimeout` is dropped: the default of 120000 ms outlasts the hold, and the call
 *   answers 200.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken } from "../../support/run.js";
import { elementNames, HTTP_TRIGGER_STEPS, trace } from "../../support/sessions.js";
import { coversBehaviour } from "../../registry/covers.js";

const CONTEXT_ID_HEADER = "e2e-context-id";
const HOLD_HEADER = "e2e-hold-ms";
const HOLD_MS = "5000";
const ATTEMPT = "Request attempt";

test.describe("sender retry and timeout", { tag: ["@engine", "@sessions"] }, () => {
  test("service-call retries a 500 until the third attempt", async ({ request, env, sessions }) => {
    coversBehaviour("service-call", "retry");
    const chain = seedChain(readCorpusState(), "service-call-retry");
    // A fresh context id per call: the count it keys lives for the SET's ttl.
    const contextId = callToken("retry");
    const before = await sessions.idsOf(chain.id);

    const response = await request.post(env.chainUrl(chain.contextPath), { headers: { "Content-Type": "application/json", [CONTEXT_ID_HEADER]: contextId }, data: {} });
    const body = await response.text();
    expect(response.status(), body).toBe(200);
    expect(JSON.parse(body), "the target counted three calls under one context id").toEqual({ attempts: 3 });

    const session = await sessions.onlyOf(chain.id, HTTP_TRIGGER_STEPS.length + 4, (each) => !before.has(each.id));
    expect(trace(session)[0].headersBefore?.[CONTEXT_ID_HEADER]).toBe(contextId);
    expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Service Call", ATTEMPT, ATTEMPT, ATTEMPT]);
    expect(trace(session).filter((each) => each.elementName === ATTEMPT).map((each) => each.executionStatus)).toEqual([
      "COMPLETED_WITH_ERRORS",
      "COMPLETED_WITH_ERRORS",
      "COMPLETED_NORMALLY",
    ]);
  });

  test("http-sender times out on a response held past it", async ({ request, env, sessions }) => {
    coversBehaviour("http-sender", "response-timeout");
    const chain = seedChain(readCorpusState(), "sender-response-timeout");
    const before = await sessions.idsOf(chain.id);

    const response = await request.post(env.chainUrl(chain.contextPath), { headers: { "Content-Type": "application/json", [HOLD_HEADER]: HOLD_MS }, data: {} });
    const body = await response.text();
    expect(response.status(), body).toBe(500);
    expect(JSON.parse(body).code, body).toBe("CIP-0103");

    const session = await sessions.onlyOf(chain.id, HTTP_TRIGGER_STEPS.length + 1, (each) => !before.has(each.id));
    expect(trace(session)[0].headersBefore?.[HOLD_HEADER]).toBe(HOLD_MS);
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Call Long Running"]);
    const sender = trace(session).find((each) => each.elementName === "Call Long Running");
    expect(sender?.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    expect(sender?.exceptionInfo?.message).toMatch(/timed out|timeout/i);
  });
});
