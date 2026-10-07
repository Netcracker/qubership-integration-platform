/**
 * A Script inside a container: one in a Loop's body, and one in each branch of a Split.
 *
 * **How a container emits its children, measured on the compiled route XML rather than inferred.**
 * The catalog emits a container's children as a route of their own, and the script is a *top-level*
 * output of that route:
 *
 * - `loop-2` compiles to `<loop>` holding `<toD uri='direct:{loopId}'/>`, and the body route is
 *   `from direct:{loopId}` — keyed on the **loop element's** id, not on the child's. One `toD` per
 *   iteration is what runs the script again.
 * - `split-2` compiles to `<multicast parallelProcessing="true">` with one `<pipeline>` per branch,
 *   each ending in `<to uri='direct:{branchId}'/>`, and every branch element is a route of its own.
 *
 * That is the measurement `specs/runtime/script-failures.spec.ts` recorded for `try-catch-finally`
 * and left open for these two: a nested script is a top-level output of a route either way, which
 * is why both engines compile it at deploy without ever recursing into a container. `choice`
 * remains unmeasured.
 *
 * **The two `http-trigger` handler values are not here.** `handleValidationAction: script` and
 * `handleChainFailureAction: script` are covered by
 * `specs/runtime/http-trigger-handlers.spec.ts`; writing them here too would put the same
 * `(family, axisPath, value)` key in `registry/elements.ts` twice.
 *
 * Both chains are ordinary corpus fixtures, so the trace below exists because the seed raised
 * `sessionsLoggingLevel` between the import and the deploy.
 */
import { test, expect } from "../../support/fixtures.js";
import { MICRO_STEP_NAMES } from "../../support/known-defect.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken } from "../../support/run.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS, trace } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

/** The loop chain's steps, in order, after the two the trigger records. */
const LOOP_STEPS = [
  "Loop",
  "Iteration",
  "Loop Pass",
  "Iteration",
  "Loop Pass",
  "Iteration",
  "Loop Pass",
  "Report Passes",
];

/** The split chain's branch steps. Compared as a set: the branches complete in a race. */
const SPLIT_BRANCH_STEPS = [
  "First Branch",
  "First Pass",
  "Main Branch",
  "Main Pass",
  "Second Branch",
  "Second Pass",
];

test("a script inside a loop runs once per iteration, and each pass reads what the one before it left", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  covers("script");

  const chain = seedChain(readCorpusState(), "script-in-container-loop.yaml");
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "loop" } });

  expect(call.response.status()).toBe(200);
  // The whole loop in one reading: three passes, in order, and the body of the last one carried
  // back out of the body route into the route that called it. `indexCleared` is the other half of
  // `loopIndexPropertyName` — the compiled `doFinally` removes the property when the loop exits, so
  // the script after the loop cannot see the index the scripts inside it read.
  expect(JSON.parse(await call.response.text())).toEqual({
    passes: "0;1;2;",
    lastBody: "pass-2",
    indexCleared: true,
  });

  const session = await sessions.byExternalId(call.token, {
    elements: HTTP_TRIGGER_STEPS.length + LOOP_STEPS.length,
  });
  // Each iteration records an `Iteration` step of its own with the script under it, so the trace
  // counts the passes independently of the property the chain accumulated.
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, ...LOOP_STEPS]);

  const passes = trace(session).filter((step) => step.elementName === "Loop Pass");
  expect(passes, "the script inside the loop did not run once per iteration").toHaveLength(3);

  // Nothing carried into the first pass, which is what makes the accumulation below a sequence
  // rather than a value that happened to be there. Read off the key list rather than by lookup: a
  // lookup is equally undefined when the step recorded no properties at all, which is the reading
  // the split case below already takes this way.
  expect(Object.keys(passes[0].propertiesBefore ?? {})).not.toContain("e2eLoopPasses");
  expect(passes[0].propertiesBefore?.["e2eLoopIndex"], "the first pass recorded no properties").toBeDefined();

  passes.forEach((pass, index) => {
    // The index the container set for this pass, and the type it set it with.
    expect(pass.propertiesBefore?.["e2eLoopIndex"]).toEqual({
      type: "java.lang.Integer",
      value: String(index),
    });
    expect(pass.bodyAfter).toBe(`pass-${index}`);
    expect(pass.propertiesAfter?.["e2eLoopPasses"]?.value).toBe(
      [...Array(index + 1).keys()].map((each) => `${each};`).join(""),
    );
    // The pass picked up where its predecessor stopped: one exchange crosses every iteration, and
    // the script is re-entered rather than re-started.
    if (index > 0) {
      expect(pass.propertiesBefore?.["e2eLoopPasses"]?.value).toBe(
        passes[index - 1].propertiesAfter?.["e2eLoopPasses"]?.value,
      );
      expect(pass.bodyBefore).toBe(passes[index - 1].bodyAfter);
    }
  });
});

test("a script in each branch of a split runs once, on that branch's own copy of the exchange", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  const chain = seedChain(readCorpusState(), "script-in-container-split.yaml");
  const seed = callToken("seed");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "e2e-seed": seed },
    data: { ping: "split" },
  });

  expect(call.response.status()).toBe(200);
  // One entry per branch, keyed by `splitName`, each holding what that branch's script wrote. Every
  // branch saw the property the pre-split script set, and none of them saw a sibling's — which is
  // deterministic rather than a race, because each branch runs on a copy.
  expect(JSON.parse(await call.response.text())).toEqual({
    main: { branch: "main", seed, isolated: true },
    first: { branch: "first", seed, isolated: true },
    second: { branch: "second", seed, isolated: true },
  });

  const session = await sessions.byExternalId(call.token, {
    elements: HTTP_TRIGGER_STEPS.length + 2 + SPLIT_BRANCH_STEPS.length,
  });
  const steps = elementNames(session);
  expect(steps.slice(0, HTTP_TRIGGER_STEPS.length + 2)).toEqual([
    ...HTTP_TRIGGER_STEPS,
    "Seed Split",
    "Split",
  ]);
  // The branches are a `multicast` with `parallelProcessing="true"`, so the order they are recorded
  // in is a race and the set is what the case is about.
  expect(steps.slice(HTTP_TRIGGER_STEPS.length + 2).sort()).toEqual(SPLIT_BRANCH_STEPS);

  for (const [name, marker] of [
    ["Main Pass", "e2eSplitMain"],
    ["First Pass", "e2eSplitFirst"],
    ["Second Pass", "e2eSplitSecond"],
  ] as const) {
    const ran = trace(session).filter((step) => step.elementName === name);
    expect(ran, `${name} did not run exactly once`).toHaveLength(1);
    // The engine's own record of the isolation the response claims: the branch was handed the seed
    // and nothing else, and the marker it wrote stayed on its own copy.
    expect(Object.keys(ran[0].propertiesBefore ?? {})).toEqual(["e2eSplitSeed"]);
    expect(ran[0].propertiesAfter?.[marker]?.value).toBe("ran");
  }
});
