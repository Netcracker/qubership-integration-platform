/**
 * The two handler axes of `http-trigger`: `handleValidationAction` and `handleChainFailureAction`,
 * every value of each, including `script`.
 *
 * One generated chain per value (`fixtures/axis-generator.ts`). A validation chain's trigger accepts
 * only XML, so a JSON call fails validation; a chain failure chain throws from the step after the
 * trigger. The sub-chain handler calls the chain trigger of `fixtures/chains/chain-callee/`.
 *
 * The trace is what tells the handlers apart. A handler keeps the status the failure set, 400 or
 * 500, and its step carries one name whatever kind it is — `Handle Validation Failure` and
 * `Failure response mapping` — so the case reads the step's own body, which is the handler's output.
 */
import { test, expect } from "../../support/fixtures.js";
import { MICRO_STEP_NAMES } from "../../support/known-defect.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { axisFixtureName, handlerReply, THROWING_STEP } from "../../fixtures/axis-generator.js";
import { callChain, element, elementNames } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

interface HandlerCase {
  axisPath: "handleValidationAction" | "handleChainFailureAction";
  value: string;
  status: number;
  /** The `code` of the platform's error body, for a default handler. */
  code?: string;
  /** The body a handler wrote, and the step that wrote it. */
  reply?: string;
  steps: string[];
}

const VALIDATION_STEP = "Handle Validation Failure";
const FAILURE_STEP = "Failure response mapping";

const CASES: HandlerCase[] = [
  { axisPath: "handleValidationAction", value: "default", status: 400, code: "QIP-0100", steps: ["Validate Request"] },
  { axisPath: "handleValidationAction", value: "script", status: 400, reply: handlerReply("validation-script"), steps: ["Validate Request", VALIDATION_STEP] },
  { axisPath: "handleValidationAction", value: "mapper-2", status: 400, reply: handlerReply("validation-mapper"), steps: ["Validate Request", VALIDATION_STEP] },
  { axisPath: "handleChainFailureAction", value: "default", status: 500, code: "QIP-0001", steps: ["Validate Request", THROWING_STEP.name] },
  { axisPath: "handleChainFailureAction", value: "script", status: 500, reply: handlerReply("failure-script"), steps: ["Validate Request", THROWING_STEP.name, FAILURE_STEP] },
  { axisPath: "handleChainFailureAction", value: "mapper-2", status: 500, reply: handlerReply("failure-mapper"), steps: ["Validate Request", THROWING_STEP.name, FAILURE_STEP] },
  // The callee's step lands inside the caller's session, under the handler's.
  { axisPath: "handleChainFailureAction", value: "chain-call", status: 500, reply: JSON.stringify({ called: "chain" }), steps: ["Validate Request", THROWING_STEP.name, FAILURE_STEP, "Callee Reply"] },
];

for (const handler of CASES) {
  const branch = `${handler.axisPath}=${handler.value}`;
  test(`${branch} answers ${handler.status} through its own handler`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
    test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
    covers("http-trigger", handler.axisPath, handler.value);
    const chain = seedChain(readCorpusState(), axisFixtureName({ family: "http-trigger", ...handler }));

    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: branch } });
    expect(call.response.status()).toBe(handler.status);
    const body = await call.response.text();
    if (handler.code) expect(JSON.parse(body).code, body).toBe(handler.code);
    else expect(body).toBe(handler.reply);

    const session = await sessions.byExternalId(call.token, { elements: handler.steps.length + 1 });
    expect(session.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    // A default handler is the absence of a handler step, which the whole list pins.
    expect(elementNames(session)).toEqual([branch, ...handler.steps]);
    const failed = handler.axisPath === "handleValidationAction" ? "Validate Request" : THROWING_STEP.name;
    expect(element(session, failed)?.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    if (handler.reply) {
      const step = handler.axisPath === "handleValidationAction" ? VALIDATION_STEP : FAILURE_STEP;
      expect(element(session, step)?.bodyAfter, `${step} did not write the reply`).toBe(handler.reply);
    }
  });
}
