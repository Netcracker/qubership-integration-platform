/**
 * A masked field never reaches the recorded session.
 *
 * `specs/api/masking.spec.ts` covers the masked-field endpoints as CRUD, which says a field can be
 * created and listed and nothing about whether the engine honors it. This is the regression that
 * leaks customer data into OpenSearch, so it is asserted where it happens: in the trace of a real
 * call.
 *
 * Two things had to be true before this could be asserted, and both live in the seed. Masked fields
 * travel to the engine **inside the deployment**, so the field is created between the import and
 * the deploy; and `maskingEnabled` has to be on in the chain's runtime properties, which is a
 * per-corpus setting rather than a per-chain one.
 *
 * The marker was measured rather than assumed: `CamelConstants.MASKING_TEMPLATE` is `******`, and a
 * call carrying `cardNumber` comes back from the session with exactly that in its place while its
 * neighbors are untouched.
 */
import { test, expect } from "../../support/fixtures.js";
import { MICRO_STEP_NAMES } from "../../support/known-defect.js";
import { MASKED_FIELD, MASKED_FIXTURE, readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain, elementNames, trace } from "../../support/sessions.js";

/** `CamelConstants.MASKING_TEMPLATE` (`CamelConstants.java:40`), copied literally. */
const MASKING_MARKER = "******";

/** A value no other fixture sends, so finding it anywhere is unambiguous. */
const SECRET = "4111111111111111";

test("a masked field is replaced by the marker everywhere in the trace", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  const chain = seedChain(readCorpusState(), MASKED_FIXTURE);

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { [MASKED_FIELD]: SECRET, note: "visible" },
  });
  expect(call.response.status()).toBe(200);
  // The response is not masked, and that is by design: masking is a logging concern. Asserting it
  // here keeps the two apart, so a change that masked the response would not read as a pass.
  expect(await call.response.text()).toContain(SECRET);

  const session = await sessions.byExternalId(call.token, { elements: 3 });
  const steps = trace(session);
  // The lookup above already waited for three steps, so a count assertion here cannot fail. The
  // names can: they say the trace belongs to this chain rather than to whatever the lookup found.
  expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "Echo"]);

  const recorded = steps.flatMap((step) => [
    step.bodyBefore ?? "",
    step.bodyAfter ?? "",
    JSON.stringify(step.headersBefore ?? {}),
    JSON.stringify(step.headersAfter ?? {}),
  ]);

  expect(
    recorded.filter((payload) => payload.includes(SECRET)),
    `the masked field "${MASKED_FIELD}" reached the session in the clear`,
  ).toEqual([]);
  // Half an assertion on its own: a trace with nothing in it would also carry no secret. The marker
  // is what says the field was seen and replaced.
  expect(recorded.some((payload) => payload.includes(MASKING_MARKER))).toBe(true);
  // And the neighbor proves masking is field-scoped rather than a blanket redaction.
  expect(recorded.some((payload) => payload.includes("visible"))).toBe(true);
});
