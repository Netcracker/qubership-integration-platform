/**
 * The families that compose one chain out of another, or out of a part of itself: chain call and
 * the chain trigger it addresses, the reuse block and its reference, and the checkpoint.
 *
 * A chain call is the one place where a trace crosses a chain boundary: the callee's step appears
 * **inside** the caller's session, under the Chain Call, which is what makes "the call reached the
 * other chain" an assertion rather than an inference from the body.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain, elementNames, failedElements } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { ABSENT_UUID } from "../../support/absent.js";

/**
 * A session id for a route probe that never reaches the handler.
 *
 * The retry path carries `{checkpointSessionId}`, and a `GET` is refused by the method restriction
 * before anything looks the session up — so this is a placeholder the router matches, not an id.
 */
const RETRY_PROBE_SESSION = "route-probe";


test("a chain call reaches the other chain's trigger and its step lands in the caller's trace", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  covers("chain-call-2");
  covers("chain-trigger-2");

  const corpus = readCorpusState();
  const caller = seedChain(corpus, "chain-call");
  const callee = seedChain(corpus, "chain-callee");

  const call = await callChain(request, env.chainUrl(caller.contextPath), {
    data: { ping: "chain-call" },
  });
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text())).toEqual({ called: "chain" });

  const session = await sessions.byExternalId(call.token, { elements: 4 });
  expect(session.chainId).toBe(caller.id);
  expect(elementNames(session)).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Chain Call",
    "Callee Reply",
  ]);

  // The callee's own HTTP trigger answers separately, which is what says the two routes are
  // distinct rather than one chain answering twice.
  const direct = await callChain(request, env.chainUrl(callee.contextPath), {
    data: { ping: "direct" },
  });
  // Asserted before the body is parsed: a 500 otherwise surfaces as a JSON syntax error naming a
  // stack trace, and the reader has to work back from that to the status.
  expect(direct.response.status()).toBe(200);
  expect(JSON.parse(await direct.response.text())).toEqual({ called: "http" });
});

test("a reuse reference runs the reused block and comes back to the calling flow", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  covers("reuse");
  covers("reuse-reference");

  const chain = seedChain(readCorpusState(), "reuse");

  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "reuse" } });
  expect(call.response.status()).toBe(200);
  // The reused block increments a property and the flow reports it afterwards, so a reference that
  // never entered the block, or never returned from it, changes this number.
  expect(JSON.parse(await call.response.text())).toEqual({ reused: 1 });

  const session = await sessions.byExternalId(call.token, { elements: 4 });
  // Measured: the reference itself records no step; what the trace shows is the reused block's own
  // element, between the trigger and the element after the reference.
  expect(elementNames(session)).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Reused Script",
    "Report",
  ]);
});

test("a checkpoint runs in the flow, and deploying it registers its retry route", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  covers("checkpoint");

  const chain = seedChain(readCorpusState(), "checkpoint");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "checkpoint" },
  });
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text())).toEqual({ checkpoint: "passed" });

  const session = await sessions.byExternalId(call.token, { elements: 4 });
  expect(elementNames(session)).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Checkpoint",
    "After Checkpoint",
  ]);
  expect(failedElements(session)).toEqual([]);

  // The title says "registers its retry route" and not "saves the exchange", and the difference is
  // measured rather than pedantic. Nothing in the API reads `engine_checkpoints_db`, and a clean
  // run empties it on the way out: `CamelDebugger.finishCheckpointSession` calls
  // `removeAllRelatedCheckpoints` for every status but COMPLETED_WITH_ERRORS. So the row a
  // successful call writes is gone before any assertion could reach it, and a checkpoint that
  // reported COMPLETED_NORMALLY while persisting nothing passes the trace assertions above.
  //
  // What the element does leave observable is the second route it compiles into:
  // `elements/checkpoint/trigger/template.hbs` opens with `from(servlet-custom:<contextPath>)`, and
  // the catalog fills that contextPath in with the chain id and the element's own id. Measured
  // against a chain built for the purpose: a GET answers **405** on the registered path — the
  // trigger restricts the method to POST — and **404** with any other element id in it, which is
  // what makes this a reading of the checkpoint's route rather than of the servlet's catch-all.
  const checkpointId = chain.elements["Checkpoint"];
  expect(checkpointId, 'the fixture carries no element named "Checkpoint"').toBeTruthy();
  const retry = env.chainUrl(
    `/chains/${chain.id}/sessions/${RETRY_PROBE_SESSION}/checkpoint-elements/${checkpointId}/retry`,
  );

  const registered = await request.get(retry, { failOnStatusCode: false });
  expect(
    registered.status(),
    "the checkpoint deployed its module half and not its trigger half, so the chain ran through it " +
      "and nothing could ever be retried from it",
  ).toBe(405);

  const wrongElement = await request.get(
    env.chainUrl(
      `/chains/${chain.id}/sessions/${RETRY_PROBE_SESSION}/checkpoint-elements/${ABSENT_UUID}/retry`,
    ),
    { failOnStatusCode: false },
  );
  expect(
    wrongElement.status(),
    "any path under the retry template answers, so the 405 above says nothing about this checkpoint",
  ).toBe(404);
});
