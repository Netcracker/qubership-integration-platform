/**
 * The axes of `service-call` other than its protocol, one value at a time, over http.
 *
 * Every case calls a seeded chain the axis generator writes, a trigger into a service call on an
 * operation of a service fixture under `fixtures/services/`, which both seeds import before their
 * chains.
 *
 * What the element sent is asserted through the mock's matchers, which **require** the body, the
 * `Authorization` header or the context header the value should produce: a request built wrong
 * matches no mock and answers 404. What it did with the answer is asserted on the response and on
 * the ordered trace, where every transformation and validation branch has a step of its own.
 *
 * `integrationOperationMethod` cannot go through a mock: the testing service matches no method. Those
 * cases call a real endpoint instead, the seeded `fixtures/chains/method-echo/` on the engine's own
 * port, which answers with the method it received. A call carrying the correlation header is always
 * mocked, so these calls carry none, and the session is found by the caller chain's id.
 *
 * Not covered here, and the registry says why: `authorizationConfiguration` m2m needs an identity
 * provider; `systemType` INTERNAL and IMPLEMENTED compile the same address, and IMPLEMENTED is not
 * offered for a service call (`ServiceField.tsx`); the template never reads
 * `afterValidation/items/type`, so its one value has no case that proves it; the engine never reads
 * `receiveCorrelationId`, so the correlation cases set it and cover only `correlationIdPosition`;
 * kafka, amqp and the four async methods belong to the broker specs under `specs/brokers/`.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import {
  axisFixtureName,
  CORRELATION_ECHO,
  CORRELATION_ID_FIELD,
  CORRELATION_ID_HEADER,
  expectEchoedMethod,
  HTTP_METHODS,
  handlerReply,
  ORDER_ID,
  ORDERS_SERVICE,
} from "../../fixtures/axis-generator.js";
import { readServiceFixture } from "../../fixtures/templating.js";
import { callToken, tokenized } from "../../support/run.js";
import { CALLER_REQUEST_ID, callChain, element, elementNames, HTTP_TRIGGER_STEPS, REQUEST_ID_HEADER, type ExecutionStatus } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { MOCK_BYPASSED, MOCKED_BODY } from "./service-call.js";
import { noChainRequestId, type RequestMatcher } from "../../support/testing-service.js";
import { strikesAsKnown } from "../../support/known-defect.js";
import type { Env } from "../../env/index.js";
import type { SchemaValue } from "../../registry/discriminators.js";

const VALIDATED = "Validations 201 application/json";
const BEFORE_STEP = "Prepare request";
const AFTER_STEP = "Handle response 201";
const AUTHORIZATION_STEP = "Authorization";
const INCOMING_AUTHORIZATION = "Bearer e2e-incoming";

/** The seeded chain of one axis value, and the id of its service call, which a mock is keyed on. */
function axisChain(axisPath: string, value: SchemaValue) {
  const chain = seedChain(readCorpusState(), axisFixtureName({ family: "service-call", axisPath, value }));
  const branch = `${axisPath}=${value}`;
  return { chain, branch, callerId: chain.elements[branch] };
}

interface MockedCase {
  axisPath: string;
  value: SchemaValue;
  /** What the value does, for the title. */
  outcome: string;
  /** What the request has to carry for the mock to answer. */
  matchers?: (env: Env) => RequestMatcher[];
  headers?: Record<string, string>;
  status: number;
  /** The response body; a `code` is the platform's error body instead. */
  reply?: string;
  code?: string;
  executionStatus: ExecutionStatus;
  /** The steps after the service call itself. */
  steps: string[];
  /** `false` for a case that proves nothing about its row. */
  declares?: false;
}

const PAYLOAD = callToken("payload");

const MOCKED_CASES: MockedCase[] = [
  { axisPath: "handleValidationAction", value: "default", outcome: "fails the chain on an invalid response", status: 500, code: "QIP-0112", executionStatus: "COMPLETED_WITH_ERRORS", steps: ["Request attempt", VALIDATED] },
  { axisPath: "handleValidationAction", value: "script", outcome: "answers from the script on an invalid response", status: 500, reply: handlerReply("validation-script"), executionStatus: "COMPLETED_WITH_ERRORS", steps: ["Request attempt", VALIDATED, "Handle Validation Failure"] },
  { axisPath: "handleValidationAction", value: "mapper-2", outcome: "answers from the mapping on an invalid response", status: 500, reply: handlerReply("validation-mapper"), executionStatus: "COMPLETED_WITH_ERRORS", steps: ["Request attempt", VALIDATED, "Handle Validation Failure"] },
  { axisPath: "afterValidation/items/type", value: "responseValidation", outcome: "passes a conforming response through", status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: ["Request attempt", VALIDATED], declares: false },

  { axisPath: "before/type", value: "none", outcome: "sends the body unchanged", matchers: () => [{ type: "contain", entityType: "body", value: PAYLOAD }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: [BEFORE_STEP, "Request attempt"] },
  { axisPath: "before/type", value: "script", outcome: "sends the body the script writes", matchers: () => [{ type: "equal", entityType: "body", value: handlerReply("before-script") }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: [BEFORE_STEP, "Request attempt"] },
  { axisPath: "before/type", value: "mapper-2", outcome: "sends the body the mapping writes", matchers: () => [{ type: "contain", entityType: "body", value: "before-mapper" }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: [BEFORE_STEP, "Request attempt"] },

  { axisPath: "after/items/type", value: "none", outcome: "answers the response unchanged", status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: ["Request attempt", AFTER_STEP] },
  { axisPath: "after/items/type", value: "script", outcome: "answers what the script writes", status: 201, reply: handlerReply("after-script"), executionStatus: "COMPLETED_NORMALLY", steps: ["Request attempt", AFTER_STEP] },
  { axisPath: "after/items/type", value: "mapper-2", outcome: "answers what the mapping writes", status: 201, reply: handlerReply("after-mapper"), executionStatus: "COMPLETED_NORMALLY", steps: ["Request attempt", AFTER_STEP] },

  // Each call carries an `Authorization` header, so `none` and `inherit` part on the same request.
  { axisPath: "authorizationConfiguration/type", value: "inherit", outcome: "passes the caller's Authorization on", headers: { Authorization: INCOMING_AUTHORIZATION }, matchers: () => [{ type: "equal", entityType: "header", entityName: "Authorization", value: INCOMING_AUTHORIZATION }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: [AUTHORIZATION_STEP, "Request attempt"] },
  { axisPath: "authorizationConfiguration/type", value: "none", outcome: "drops the caller's Authorization", headers: { Authorization: INCOMING_AUTHORIZATION }, matchers: () => [{ type: "empty", entityType: "header", entityName: "Authorization" }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: [AUTHORIZATION_STEP, "Request attempt"] },
  { axisPath: "authorizationConfiguration/type", value: "basic", outcome: "sends basic credentials", headers: { Authorization: INCOMING_AUTHORIZATION }, matchers: () => [{ type: "equal", entityType: "header", entityName: "Authorization", value: `Basic ${Buffer.from("e2e-user:e2e-password").toString("base64")}` }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: [AUTHORIZATION_STEP, "Request attempt"] },
  { axisPath: "authorizationConfiguration/type", value: "bearer", outcome: "sends the bearer token", headers: { Authorization: INCOMING_AUTHORIZATION }, matchers: () => [{ type: "equal", entityType: "header", entityName: "Authorization", value: "Bearer e2e-token" }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: [AUTHORIZATION_STEP, "Request attempt"] },

  // The trigger strips the caller's context headers; only propagation puts them back on the request.
  { axisPath: "propagateContext", value: true, outcome: "sends the caller's request id", headers: { [REQUEST_ID_HEADER]: CALLER_REQUEST_ID }, matchers: () => [{ type: "equal", entityType: "header", entityName: REQUEST_ID_HEADER, value: CALLER_REQUEST_ID }], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: ["Request attempt"] },
  { axisPath: "propagateContext", value: false, outcome: "sends no request id", headers: { [REQUEST_ID_HEADER]: CALLER_REQUEST_ID }, matchers: (env) => [noChainRequestId(env)], status: 201, reply: MOCKED_BODY, executionStatus: "COMPLETED_NORMALLY", steps: ["Request attempt"] },
];

const MOCKED_TAGS = ["@engine", "@catalog", "@sessions", "@testing-service", "@tier2"];

for (const each of MOCKED_CASES) {
  test(`${each.axisPath}=${each.value}: a service call ${each.outcome}`, { tag: MOCKED_TAGS }, async ({ request, env, sessions, testingService, run }) => {
    // The afterValidation case declares no row: the template never reads the entry's type.
    if (each.declares !== false) covers("service-call", each.axisPath, each.value);
    const { chain, branch, callerId } = axisChain(each.axisPath, each.value);
    const response = { status: 201, body: MOCKED_BODY, headers: [{ name: "Content-Type", value: "application/json" }] };
    await testingService.withMock({ name: tokenized(run, `service-call-${branch}`), reference: { chainId: chain.id, elementId: callerId }, response, matchers: each.matchers?.(env) }, async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { payload: PAYLOAD }, headers: each.headers });
      const body = await call.response.text();
      await strikesAsKnown(MOCK_BYPASSED, async () => {
        expect(call.response.status(), body).toBe(each.status);
        if (each.code) expect(JSON.parse(body).code, body).toBe(each.code);
        else expect(body).toBe(each.reply);
      });
      const steps = [branch, ...each.steps];
      const session = await sessions.byExternalId(call.token, { elements: steps.length + 2 });
      expect(session.executionStatus).toBe(each.executionStatus);
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, ...steps]);
    });
  });
}

/** `RegExp` source matching `text` literally. */
function literally(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// The template tells EXTERNAL from the rest (`service-call/template.hbs:234-243`): EXTERNAL goes to the
// egress gateway's route for the element, anything else to the environment's address. The mock
// answers either, so the URI the producer was handed is read off the request step.
const SYSTEM_TYPES = [
  // INTERNAL declares no row: IMPLEMENTED compiles the same address, so this case cannot tell them apart.
  { value: "INTERNAL", declares: false, uri: () => new RegExp(`^${literally(readServiceFixture(ORDERS_SERVICE).address)}/orders/${ORDER_ID}$`) },
  { value: "EXTERNAL", declares: true, uri: (callerId: string) => new RegExp(`^http://[^/]+/system/${callerId}/[0-9a-f]{40}/orders/${ORDER_ID}$`) },
] as const;

for (const { value, declares, uri } of SYSTEM_TYPES) {
  test(`systemType=${value}: a service call addresses ${value === "EXTERNAL" ? "the egress gateway" : "the environment"}`, { tag: MOCKED_TAGS }, async ({ request, env, sessions, testingService, run }) => {
    if (declares) covers("service-call", "systemType", value);
    const { chain, branch, callerId } = axisChain("systemType", value);
    await testingService.withMock({ name: tokenized(run, `service-call-${branch}`), reference: { chainId: chain.id, elementId: callerId }, response: { status: 201, body: MOCKED_BODY } }, async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
      const body = await call.response.text();
      await strikesAsKnown(MOCK_BYPASSED, async () => expect(call.response.status(), body).toBe(201));
      const session = await sessions.byExternalId(call.token, { elements: 4 });
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch, "Request attempt"]);
      expect(element(session, "Request attempt")?.headersBefore?.CamelHttpUri).toMatch(uri(callerId));
    });
  });
}

const POSITIONS = ["header", "body"] as const;

for (const position of POSITIONS) {
  test(`correlationIdPosition ${position}: a service call receives the correlation id the response carries`, { tag: MOCKED_TAGS }, async ({ request, env, sessions, testingService, run }) => {
    covers("service-call", "correlationIdPosition", position);
    const sent = callToken("correlation");
    const { chain, branch, callerId } = axisChain("correlationIdPosition", position);
    const response = position === "header"
      ? { status: 201, body: MOCKED_BODY, headers: [{ name: CORRELATION_ID_HEADER, value: sent }] }
      : { status: 201, body: JSON.stringify({ [CORRELATION_ID_FIELD]: sent }), headers: [{ name: "Content-Type", value: "application/json" }] };
    await testingService.withMock({ name: tokenized(run, `service-call-${branch}`), reference: { chainId: chain.id, elementId: callerId }, response }, async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
      const body = await call.response.text();
      await strikesAsKnown(MOCK_BYPASSED, async () => expect(call.response.status(), body).toBe(201));
      const session = await sessions.byExternalId(call.token, { elements: 5 });
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch, "Request attempt", CORRELATION_ECHO.name]);
      expect((JSON.parse(body) as { correlationId: unknown }).correlationId).toBe(sent);
    });
  });
}

// The method service's address is the engine's chain routes, and each operation's path is the echo's
// own route.
for (const method of HTTP_METHODS) {
  test(`integrationOperationMethod=${method}: a service call sends ${method}`, { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("service-call", "integrationOperationMethod", method);
    const { chain, branch } = axisChain("integrationOperationMethod", method);
    const before = await sessions.idsOf(chain.id);
    await expectEchoedMethod(request, env.chainUrl(chain.contextPath), method);
    const session = await sessions.onlyOf(chain.id, 4, (each) => !before.has(each.id));
    expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch, "Request attempt"]);
  });
}
