/**
 * The idempotency and correlation id axes of `http-trigger`.
 *
 * Idempotency: one generated chain per value, each called twice with one key. The key is minted per
 * case, because the store is Postgres (`PostgresIdempotentRepository`) and outlives the run for
 * `keyExpiry` seconds: a key two cases or two runs share turns a first call into a duplicate. The
 * first call runs the chain whatever the value; the second is where the values part, and the trace
 * says which way: `Idempotency` is the trigger's own step, absent when the check is off, and
 * `Callee Reply` is the step `fixtures/chains/chain-callee/` runs inside the caller's session.
 *
 * Correlation id: one generated chain per `correlationIdPosition` value, each echoing the
 * `correlationId` exchange property the trigger set. Both chains also set `receiveCorrelationId: true`.
 * The engine never reads that flag, so neither of its rows is covered.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import {
  axisFixtureName,
  CORRELATION_ECHO,
  CORRELATION_ID_FIELD,
  CORRELATION_ID_HEADER,
  IDEMPOTENCY_KEY_HEADER,
  PASSED_STEP,
} from "../../fixtures/axis-generator.js";
import { MICRO_STEP_NAMES } from "../../support/known-defect.js";
import { callToken } from "../../support/run.js";
import { callChain, element, elementNames, type ExecutionStatus } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

const PASSED = JSON.stringify({ called: "downstream" });
const IDEMPOTENCY_STEP = "Idempotency";

interface DuplicateCase {
  axisPath: "idempotency/enabled" | "idempotency/actionOnDuplicate";
  value: boolean | string;
  /** What the second call does, for the title. */
  outcome: string;
  status: number;
  /** The duplicate's body; a `code` is the platform's error body instead. */
  reply?: string;
  code?: string;
  executionStatus: ExecutionStatus;
  /** The duplicate's steps after the trigger and `Validate Request`. */
  steps: string[];
}

const DUPLICATES: DuplicateCase[] = [
  { axisPath: "idempotency/enabled", value: false, outcome: "runs the chain again", status: 200, reply: PASSED, executionStatus: "COMPLETED_NORMALLY", steps: [PASSED_STEP.name] },
  // No `reply`: the body is the request echoed back, because the check interrupts the exchange before anything writes one.
  { axisPath: "idempotency/actionOnDuplicate", value: "ignore", outcome: "answers 202 and stops", status: 202, executionStatus: "COMPLETED_NORMALLY", steps: [IDEMPOTENCY_STEP] },
  { axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", outcome: "fails the exchange", status: 500, code: "QIP-0001", executionStatus: "COMPLETED_WITH_ERRORS", steps: [IDEMPOTENCY_STEP] },
  { axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", outcome: "answers from the sub-chain", status: 200, reply: JSON.stringify({ called: "chain" }), executionStatus: "COMPLETED_NORMALLY", steps: [IDEMPOTENCY_STEP, "Callee Reply"] },
];

for (const duplicate of DUPLICATES) {
  const branch = `${duplicate.axisPath}=${duplicate.value}`;
  test(`${branch}: a repeated key ${duplicate.outcome}`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
    test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
    covers("http-trigger", duplicate.axisPath, duplicate.value);
    // Every actionOnDuplicate chain enables the check.
    if (duplicate.axisPath === "idempotency/actionOnDuplicate") covers("http-trigger", "idempotency/enabled", true);
    const chain = seedChain(readCorpusState(), axisFixtureName({ family: "http-trigger", ...duplicate }));
    const url = env.chainUrl(chain.contextPath);
    const key = callToken("idempotency");
    const headers = { [IDEMPOTENCY_KEY_HEADER]: key };
    // A body only this case sends, so an echoed request cannot pass for another answer.
    const data = { key };
    const enabled = duplicate.value !== false;
    const before = ["Validate Request", ...(enabled ? [IDEMPOTENCY_STEP] : [])];

    const first = await callChain(request, url, { data, headers });
    expect(first.response.status()).toBe(200);
    expect(await first.response.text()).toBe(PASSED);
    const firstSession = await sessions.byExternalId(first.token, { elements: before.length + 2 });
    expect(elementNames(firstSession)).toEqual([branch, ...before, PASSED_STEP.name]);

    const second = await callChain(request, url, { data, headers });
    expect(second.response.status()).toBe(duplicate.status);
    const body = await second.response.text();
    if (duplicate.code) expect(JSON.parse(body).code, body).toBe(duplicate.code);
    else expect(body).toBe(duplicate.reply ?? JSON.stringify(data));

    const secondSession = await sessions.byExternalId(second.token, { elements: duplicate.steps.length + 2 });
    expect(secondSession.executionStatus).toBe(duplicate.executionStatus);
    expect(elementNames(secondSession)).toEqual([branch, "Validate Request", ...duplicate.steps]);
    if (duplicate.code) {
      expect(element(secondSession, IDEMPOTENCY_STEP)?.exceptionInfo?.message).toBe("Duplicated idempotency key");
    }
  });
}

const POSITIONS = [
  { fixture: { family: "http-trigger", axisPath: "receiveCorrelationId", value: true }, position: "header" },
  { fixture: { family: "http-trigger", axisPath: "correlationIdPosition", value: "body" }, position: "body" },
] as const;

for (const { fixture, position } of POSITIONS) {
  const branch = `${fixture.axisPath}=${fixture.value}`;
  test(`correlationIdPosition ${position} receives the correlation id the call carries`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
    test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
    covers("http-trigger", "correlationIdPosition", position);
    const chain = seedChain(readCorpusState(), axisFixtureName(fixture));
    const sent = callToken("correlation");
    const options = position === "header"
      ? { data: {}, headers: { [CORRELATION_ID_HEADER]: sent } }
      : { data: { [CORRELATION_ID_FIELD]: sent } };
    const call = await callChain(request, env.chainUrl(chain.contextPath), options);
    expect(call.response.status()).toBe(200);
    const echoed = (JSON.parse(await call.response.text()) as { correlationId: unknown }).correlationId;
    const session = await sessions.byExternalId(call.token, { elements: 3 });
    expect(elementNames(session)).toEqual([branch, "Validate Request", CORRELATION_ECHO.name]);
    expect(echoed).toBe(sent);
    expect(session.correlationId).toBe(sent);
  });
}
