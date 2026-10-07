/**
 * The seeded corpus is still there, still deployed, and still answering when `runtime` runs.
 *
 * That is the whole subject. The tier-1 chains and their trace assertions live in the other runtime
 * specs; this file exists so the seed's own gate has something to prove, because a gate over an
 * empty project is vacuous — `--project=seed` on its own runs the teardown immediately afterwards
 * and proves only that a corpus can be created and destroyed.
 */
import { test, expect } from "../../support/fixtures.js";
import { MICRO_STEP_NAMES } from "../../support/known-defect.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken } from "../../support/run.js";
import { callChain, elementNames, failedElements } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

test("the seeded http-echo chain outlives the seed project and answers on its route", { tag: ["@engine", "@tier1"] }, async ({ request, env }) => {
  // The two element families this chain is made of, at their defaults. This case closes the element
  // rows; the axis values have cases of their own.
  covers("http-trigger");
  covers("header-modification");

  // Read from the corpus rather than compared against this run's token. `--no-deps` is the
  // documented way to re-run one case against a corpus that is already up, and `globalSetup` mints
  // a fresh token on every run — so a guard here would reject the workflow the README prescribes,
  // and it would reject it in the one spec the README uses as its example. No other runtime spec
  // carries one.
  const corpus = readCorpusState();
  const chain = seedChain(corpus, "http-echo");
  // Without an explicit content type the servlet reads the body as form data and the chain fails
  // with "Invalid parameter, expected to be a pair", which reads as a broken chain.
  const answer = await request.post(env.chainUrl(chain.contextPath), {
    headers: { "Content-Type": "application/json" },
    data: { ping: "runtime" },
  });

  expect(answer.status()).toBe(200);
  expect(JSON.parse(await answer.text()), "the trigger echoes what it was sent").toEqual({
    ping: "runtime",
  });
  // The header modification is what says this is the fixture's chain rather than some other
  // deployment that happens to own the path. Against the corpus's token, because that is what the
  // template was rendered with: this run's token is a different string whenever the corpus was
  // seeded by an earlier one.
  expect(answer.headers()["e2e-fixture"]).toBe(corpus.run);
});

test("a call correlated by its own token finds the trace the engine recorded", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  const chain = seedChain(readCorpusState(), "http-echo");

  const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "trace" },
  });
  expect(response.status()).toBe(200);

  // Three steps, and the lookup waits for all three: a session is queryable before its elements
  // finish being indexed, so a read that stops at the first one asserts over a partial trace.
  const session = await sessions.byExternalId(token, { elements: 3 });
  expect(session.chainId).toBe(chain.id);
  expect(session.externalSessionCipId).toBe(token);
  // Recursive, and that is the assertion: `Validate Request` is a child of the trigger, so a
  // reader that stops at the top level cannot see which branch an exchange took.
  expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "Header Modification"]);
  expect(failedElements(session)).toEqual([]);
});

test("the same chain, called twice, gives each call its own session", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "http-echo");

  // The reason the token is per call and not per run: `external-id` answers a *single* session, so
  // a shared token would hand a spec whichever of these two the index returned.
  const first = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: 1 } });
  const second = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: 2 } });
  expect(first.token).not.toBe(second.token);

  const one = await sessions.byExternalId(first.token);
  const two = await sessions.byExternalId(second.token);
  expect(one.id).not.toBe(two.id);
});

test("a token no chain sent fails inside its budget and names the token", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ sessions }) => {
  const never = callToken("never-sent");

  // The mutation check for the lookup. `GET /v1/sessions/external-id/{unknown}` answers 404 with
  // the same body as a session that has not landed yet, so nothing in the response separates the
  // two: the helper separates them by bounding the wait and naming the token, and this is what
  // proves it does. A short budget on purpose — the case is that nothing ever arrives.
  let failure = "";
  try {
    await sessions.byExternalId(never, { timeout: 3_000 });
  } catch (cause) {
    failure = String(cause);
  }
  expect(failure, "the lookup returned a session for a token no chain ever sent").not.toBe("");
  expect(failure).toContain(never);
  expect(failure).toContain("external-session-cip-id");
});
