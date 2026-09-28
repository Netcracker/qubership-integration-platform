/**
 * The testing service works as this suite's **tool**, asserted before any runtime spec leans on it.
 *
 * Every spec that mocks an outbound call depends on two behaviors, and neither is this suite's to
 * fix: a registered mock answers in place of the real endpoint, and an uncorrelated call still
 * reaches the real one. A broken testing service otherwise surfaces as dozens of red runtime specs
 * that blame the platform, and the diagnosis costs an afternoon.
 *
 * The `testing-service-*` specs under `specs/api/` and `specs/runtime/` cover the testing service
 * as a **target**. This file covers it as a dependency, asserts those two behaviors, and asserts
 * nothing else.
 *
 * The second call deliberately carries **no** correlation header. Redirection is keyed on the
 * `TESTING_SESSION_ID` exchange property, which `HttpTriggerProcessor` sets from that header, so a
 * correlated call with no matching mock is answered **404 by the testing service** — which is not
 * the real endpoint, and reading it as one is exactly the mistake this file exists to prevent.
 *
 * The mock is named with the run token, and that is not bookkeeping. It is keyed on
 * `(chainId, elementId)` out of a fixture that freezes both, so a mock that survives this case goes
 * on answering for the sender on every later run — a red assertion in a run that did nothing wrong,
 * against a stack whose only trace of the cause is a row named after a run that is long over. The
 * token is what lets `findResidue` see it and the sweep remove it.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { CORRELATION_HEADER } from "../../support/testing-service.js";
import { callToken, tokenized } from "../../support/run.js";
import { covers } from "../../registry/covers.js";

/** The seed fixture that calls out, and the element inside it a mock is keyed on. */
const FIXTURE = "http-out";
const SENDER = "HTTP Sender";

/** What the real endpoint answers, and what a mock has to be distinguishable from. */
const REAL_ENDPOINT_MARKER = '"status":"UP"';

test("an endpoint mock answers for a correlated call, and the real endpoint answers without one", { tag: ["@testing-service", "@engine", "@tier1"] }, async ({ env, request, run, testingService }) => {
  // The fixture's outbound call is an `http-sender` at its defaults, and the second half of this
  // case is the only place in the suite where one reaches a real endpoint.
  covers("http-sender");

  const chain = seedChain(readCorpusState(), FIXTURE);
  const elementId = chain.elements[SENDER];
  expect(elementId, `fixture ${FIXTURE} carries no element named "${SENDER}"`).toBeTruthy();

  const reference = { chainId: chain.id, elementId };
  const name = tokenized(run, "mock");
  const mock = await testingService.createMock({ name, reference, response: { status: 200, body: '{"mocked":true}' } });

  try {
    const mocked = await request.post(env.chainUrl(chain.contextPath), {
      headers: { "Content-Type": "application/json", [CORRELATION_HEADER]: callToken() },
      data: { ping: "mocked" },
    });
    expect(mocked.status()).toBe(200);
    expect(
      await mocked.text(),
      "the mock did not answer: the outbound call reached something else",
    ).toContain('"mocked":true');
  } finally {
    // Reported rather than thrown. A delete that throws out of a `finally` replaces whatever the
    // body was failing on with its own message, and the reading it would have replaced is the one
    // worth having; the assertion below fails on the same condition anyway, and the run-token sweep
    // is what removes the mock either way.
    await testingService.deleteMock(mock.id).catch((cause: unknown) => {
      console.error(`[teardown] mock ${name} was not deleted: ${String(cause)}`);
    });
  }

  expect(await testingService.mocksOn(reference), "the mock outlived its own delete").toEqual([]);

  const live = await request.post(env.chainUrl(chain.contextPath), {
    headers: { "Content-Type": "application/json" },
    data: { ping: "live" },
  });
  expect(live.status()).toBe(200);
  expect(
    await live.text(),
    "an uncorrelated call did not reach the real endpoint",
  ).toContain(REAL_ENDPOINT_MARKER);
});
