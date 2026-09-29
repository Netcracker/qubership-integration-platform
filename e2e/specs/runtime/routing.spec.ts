/**
 * The routing families at tier 1: condition, the deprecated choice, split, and loop.
 *
 * Each case asserts the **response and the trace**, and that pairing is the point. A branch that
 * takes the wrong path still answers 200 with a body some other branch could have produced, so a
 * status-code assertion cannot see it; the trace names the branch that ran. The trace exists only
 * because the seed raised the session logging level between import and deploy — an empty trace here
 * means suspecting that step before suspecting the chain.
 *
 * Tier 1 runs each element at its defaults, so these cases close the **element** rows of the
 * registry and no axis row.
 */
import { test, expect } from "../../support/fixtures.js";
import { MICRO_STEP_NAMES } from "../../support/known-defect.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain, elementNames, failedElements } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

/** The header the branching fixtures read. `${header[e2e-branch]}` in the fixtures' predicates. */
const BRANCH_HEADER = "e2e-branch";

test("condition sends the exchange down the branch its predicate selects", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  covers("condition");
  covers("if");
  covers("else");

  const chain = seedChain(readCorpusState(), "condition");

  const taken = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { [BRANCH_HEADER]: "if" },
    data: { ping: "condition" },
  });
  expect(taken.response.status()).toBe(200);
  expect(JSON.parse(await taken.response.text())).toEqual({ branch: "if" });
  expect(elementNames(await sessions.byExternalId(taken.token, { elements: 5 }))).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Condition",
    "If",
    "If Branch",
  ]);

  const other = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { [BRANCH_HEADER]: "anything-else" },
    data: { ping: "condition" },
  });
  expect(other.response.status()).toBe(200);
  expect(JSON.parse(await other.response.text())).toEqual({ branch: "else" });
  // The assertion the status code cannot make: both calls answer 200, and only the trace says
  // which of the two branches the exchange went through.
  expect(elementNames(await sessions.byExternalId(other.token, { elements: 5 }))).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Condition",
    "Else",
    "Else Branch",
  ]);
});

test("choice, deprecated, still routes and still records its branch", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  covers("choice");
  covers("when");
  covers("otherwise");

  const chain = seedChain(readCorpusState(), "choice");

  const matched = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { [BRANCH_HEADER]: "when" },
    data: { ping: "choice" },
  });
  expect(matched.response.status()).toBe(200);
  expect(JSON.parse(await matched.response.text())).toEqual({ branch: "when" });
  expect(elementNames(await sessions.byExternalId(matched.token, { elements: 5 }))).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Choice",
    "When",
    "When Branch",
  ]);

  const fallback = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { [BRANCH_HEADER]: "no" },
    data: { ping: "choice" },
  });
  expect(fallback.response.status()).toBe(200);
  expect(JSON.parse(await fallback.response.text())).toEqual({ branch: "otherwise" });
  // The full step list rather than a `toContain`: a run that took the `When` branch as well would
  // still hold "Otherwise Branch", and the fallback taken *instead of* the match is the case.
  expect(elementNames(await sessions.byExternalId(fallback.token, { elements: 5 }))).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Choice",
    "Otherwise",
    "Otherwise Branch",
  ]);
});

test("split runs both branches and the aggregation keys the result by split name", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  covers("split-2");
  covers("main-split-element-2");
  covers("split-element-2");

  const chain = seedChain(readCorpusState(), "split");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "split" },
  });
  expect(call.response.status()).toBe(200);
  // Measured: `chainsAggregationStrategy` answers a body keyed by each branch's `splitName`, with
  // the main branch's own body under `main`. A branch that never ran leaves its key out.
  expect(JSON.parse(await call.response.text())).toEqual({
    main: { branch: "main" },
    aux: { ping: "split" },
  });

  const session = await sessions.byExternalId(call.token, { elements: 7 });
  const steps = elementNames(session);
  expect(steps.slice(0, 3)).toEqual(["HTTP Trigger", "Validate Request", "Split"]);
  // The branches run in parallel — the compiled `multicast` carries `parallelProcessing="true"` —
  // so which of them the trace records first is a race, and asserting the order makes this spec
  // fail for a reason that is not the platform's. The set is what the case is about.
  expect(steps.slice(3).sort()).toEqual([
    "Aux Branch",
    "Aux Script",
    "Main Branch",
    "Main Script",
  ]);
  expect(failedElements(session)).toEqual([]);
});

test("loop repeats its body as many times as the expression says", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  covers("loop-2");

  const chain = seedChain(readCorpusState(), "loop");

  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "loop" } });
  expect(call.response.status()).toBe(200);
  // The count is in the body rather than only in the trace, so a loop that runs once or forever
  // fails on the response too.
  expect(JSON.parse(await call.response.text())).toEqual({ iterations: 3 });

  // Ten steps: the trigger and its validation, the loop, three passes of two steps each, and the
  // report afterwards.
  const session = await sessions.byExternalId(call.token, { elements: 10 });
  // Each pass records an `Iteration` step of its own, so the trace counts the iterations
  // independently of what the chain's own script counted.
  expect(elementNames(session).filter((name) => name === "Iteration Script")).toHaveLength(3);
});
