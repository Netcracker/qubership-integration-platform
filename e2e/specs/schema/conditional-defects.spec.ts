/**
 * The `matches` of every `ConditionalDefect`, against the failure messages it has to recognize and the
 * ones it must leave a real failure. No stack.
 */
import { test, expect, type APIResponse } from "@playwright/test";
import { MOCK_BYPASSED } from "../runtime/service-call.js";
import { KILL_NOT_FOUND_UNSPECIFIC, LIMIT_REFUSED_WITH_400, LIVE_EXCHANGE_UNLISTED } from "../global/micro-defects.js";
import { withHeldAnswer, type HeldCall } from "../../support/held-call.js";
import { CONTEXT_STORE_UNTRANSACTED } from "../runtime/context-storage.js";
import {
  PUBSUB_EMULATOR_BYPASSED,
  SFTP_TRIGGER_IDLE,
  linesOfChain,
  pubsubAuthFailure,
  withEngineLines,
} from "../brokers/micro-defects.js";
import type { Env } from "../../env/index.js";

const TAGS = { tag: ["@infra", "@tier1"] };

test("a mocked service call that reached its real address is the known defect, and an unmatched mock is not", TAGS, () => {
  // The two failures the micro engine produced, as `strikesAsKnown` reads them.
  const unresolved = [
    "Error: {",
    '  "code" : "QIP-0101",',
    '  "reason" : "Chain execution failed due to requesting missing endpoint",',
    "}",
    "",
    "expect(received).toBe(expected) // Object.is equality",
  ].join("\n");
  const egress = "Error: no healthy upstream\n\nexpect(received).toBe(expected) // Object.is equality\n\nExpected: 201\nReceived: 503";
  // A test case run of `http-out`, whose rule read the real endpoint's answer.
  const health = [
    `Error: the case run's outbound call was not mocked: '{"status":"UP","groups":["liveness","readiness"],"components":{"ping":{"status":"UP"}}}' doesn't contain '"mocked":"e2e-s7rodt-runs-mocked"'`,
    "",
    "expect(received).toEqual(expected) // deep equality",
  ].join("\n");
  expect(MOCK_BYPASSED.matches(unresolved)).toBe(true);
  expect(MOCK_BYPASSED.matches(egress)).toBe(true);
  expect(MOCK_BYPASSED.matches(health)).toBe(true);

  // The testing service answers a request no mock matched with 404.
  expect(MOCK_BYPASSED.matches("Error: no mock matched: the element sent another path, query or body. \n\nExpected: 201\nReceived: 404")).toBe(false);
  expect(MOCK_BYPASSED.matches("Error: expect(received).toBe(expected)\n\nExpected: 201\nReceived: 500")).toBe(false);
  // The mock answered, with another body than the rule expects.
  expect(MOCK_BYPASSED.matches(`Error: the case run's outbound call was not mocked: '{"mocked":"other"}' doesn't contain '"mocked":"e2e-s7rodt-runs-mocked"'`)).toBe(false);
});

test("a context SET the micro engine ran outside a transaction is the known defect, and another failure is not", TAGS, () => {
  // The failure as `expectStored` in misc-elements.spec.ts reports it: the step's recorded exception.
  const untransacted = [
    "Error: Transaction is not active, consider adding @Transactional to your method to automatically activate one.",
    "",
    "expect(received).toBe(expected) // Object.is equality",
    "",
    "Expected: 200",
    "Received: 500",
  ].join("\n");
  expect(CONTEXT_STORE_UNTRANSACTED.matches(untransacted)).toBe(true);

  expect(CONTEXT_STORE_UNTRANSACTED.matches("Error: expect(received).toBe(expected)\n\nExpected: 200\nReceived: 500")).toBe(false);
  expect(CONTEXT_STORE_UNTRANSACTED.matches("Error: Connection refused\n\nExpected: 200\nReceived: 500")).toBe(false);
});

/** What `withEngineLines` throws when `wait` fails and the engine log reads `log`. */
async function engineLinesFailure(log: string, what: string, pick: (line: string) => boolean): Promise<string> {
  const env = { engineLogs: async () => log } as unknown as Env;
  const failed = withEngineLines(env, "2026-10-14T06:18:41.203Z", what, pick, async () => {
    throw new Error("sessions of e2e0b001-0000-4000-8000-000000000001\n\nExpected length: 1\nReceived length: 0");
  });
  return String(await failed.catch((cause: unknown) => cause));
}

const SUBSCRIBER_LOG = [
  "x{5}[2026-10-14T06:18:42.251] [ERROR] [request_id=               ] terminated streaming with exception: com.google.api.gax.rpc.UnauthenticatedException: com.google.api.gax.rpc.UnauthenticatedException: io.grpc.StatusRuntimeException: UNAUTHENTICATED: Failed computing credential metadata",
  "\tat com.google.api.gax.rpc.ApiExceptionFactory.createException(ApiExceptionFactory.java:116)",
  "Caused by: io.grpc.StatusRuntimeException: UNAUTHENTICATED: Failed computing credential metadata",
].join("\n");

test("a pub/sub call the micro engine sent to Google is the known defect, and another pub/sub failure is not", TAGS, async () => {
  // A sender step's recorded exception, as `expectSent` in pubsub.spec.ts reports it.
  const sender = [
    "Error: com.google.api.gax.rpc.UnauthenticatedException: io.grpc.StatusRuntimeException: UNAUTHENTICATED: Failed computing credential metadata",
    "",
    "expect(received).toBe(expected) // Object.is equality",
    "",
    "Expected: 200",
    "Received: 500",
  ].join("\n");
  expect(PUBSUB_EMULATOR_BYPASSED.matches(sender)).toBe(true);
  // A trigger case that found no session, with the subscriber's failures the micro pod logged.
  const trigger = await engineLinesFailure(SUBSCRIBER_LOG, "naming UNAUTHENTICATED", pubsubAuthFailure);
  expect(trigger).toContain("the engine logged 2 lines naming UNAUTHENTICATED");
  expect(PUBSUB_EMULATOR_BYPASSED.matches(trigger)).toBe(true);

  // No session, and a subscriber that logged no authentication failure.
  expect(PUBSUB_EMULATOR_BYPASSED.matches(await engineLinesFailure("", "naming UNAUTHENTICATED", pubsubAuthFailure))).toBe(false);
  expect(PUBSUB_EMULATOR_BYPASSED.matches("Error: expect(received).toBe(expected)\n\nExpected: 200\nReceived: 500")).toBe(false);
});

test("an sftp trigger that never ran its chain is the known defect, and a run that recorded no session is not", TAGS, async () => {
  const chain = "e2e0b001-0000-4000-8000-000000000001";
  const idle = await engineLinesFailure(SUBSCRIBER_LOG, `for chain ${chain}`, linesOfChain(chain));
  expect(idle).toContain(`the engine logged 0 lines for chain ${chain}`);
  expect(SFTP_TRIGGER_IDLE.matches(idle)).toBe(true);

  // The chain ran, so the poll picked the file up and something else lost the session.
  const ran = await engineLinesFailure(`[INFO ] [chain_id=${chain}] [chain=e2e-x-sftp-trigger-basic] Session started`, `for chain ${chain}`, linesOfChain(chain));
  expect(ran).toContain(`the engine logged 1 lines for chain ${chain} since 2026-10-14T06:18:41.203Z; the first: [INFO ] [chain_id=${chain}]`);
  expect(SFTP_TRIGGER_IDLE.matches(ran)).toBe(false);
  expect(SFTP_TRIGGER_IDLE.matches("Error: sessions of x\n\nExpected length: 1\nReceived length: 0")).toBe(false);
});

test("withEngineLines returns what a successful wait returns and reads no log", TAGS, async () => {
  let reads = 0;
  const env = { engineLogs: async () => { reads++; return ""; } } as unknown as Env;
  expect(await withEngineLines(env, "2026-10-14T06:18:41.203Z", "naming UNAUTHENTICATED", pubsubAuthFailure, async () => 42)).toBe(42);
  expect(reads).toBe(0);
});

/** What `withHeldAnswer` throws when `step` fails with `failure` and the held call answered `status` and `body`. */
async function heldAnswerFailure(failure: string, status: number, body: string): Promise<string> {
  const response = { status: () => status, text: async () => body } as unknown as APIResponse;
  const call: HeldCall = { token: "t", answer: Promise.resolve(response) };
  const thrown = await withHeldAnswer(call, async () => {
    throw new Error(failure);
  }).catch((error: unknown) => error);
  return String(thrown);
}

test("an exchange the micro engine never listed while it was held is the known defect, and one that was not held is not", TAGS, async () => {
  const held = '{"element":"long-running","heldMs":12000}';
  const fromCatalog = await heldAnswerFailure("no live exchange for chain c1 was ever listed", 200, held);
  expect(fromCatalog).toContain(`the held call answered 200 ${held}`);
  expect(LIVE_EXCHANGE_UNLISTED.matches(fromCatalog)).toBe(true);
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("the engine never reported an exchange for e2e-x-long-running", 200, held))).toBe(true);

  // The call failed, or answered without holding, so nothing was in flight to list.
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("no live exchange for chain c1 was ever listed", 503, "no healthy upstream"))).toBe(false);
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("no live exchange for chain c1 was ever listed", 200, '{"element":"other"}'))).toBe(false);
  // The exchange was listed, and a later assertion failed.
  expect(LIVE_EXCHANGE_UNLISTED.matches(await heldAnswerFailure("expect(received).toBe(expected)\n\nExpected: true\nReceived: false", 200, held))).toBe(false);
});

test("withHeldAnswer returns what a successful step returns", TAGS, async () => {
  const call: HeldCall = { token: "t", answer: new Promise<APIResponse>(() => undefined) };
  expect(await withHeldAnswer(call, async () => 42)).toBe(42);
});

test("an unknown deployment the micro engine reports as an unknown exchange is the known defect, and another 404 is not", TAGS, () => {
  const micro = `Error: the engine answered {"serviceName":"Engine","errorMessage":"No live exchange found for deployment id no-such-deployment"}\n\nexpect(received).toContain(expected) // indexOf`;
  expect(KILL_NOT_FOUND_UNSPECIFIC.matches(micro)).toBe(true);
  expect(KILL_NOT_FOUND_UNSPECIFIC.matches("Error: the engine answered Not Found\n\nexpect(received).toContain(expected) // indexOf")).toBe(false);
});

test("limit=0 refused with the micro engine's 400 is the known divergence, and another status is not", TAGS, () => {
  const micro = `Error: the engine answered {"title":"Constraint Violation","status":400,"violations":[{"field":"getLiveExchanges.limit","message":"must be greater than 0"}]}\n\nExpected: 500\nReceived: 400`;
  expect(LIMIT_REFUSED_WITH_400.matches(micro)).toBe(true);
  expect(LIMIT_REFUSED_WITH_400.matches("Error: the engine answered no healthy upstream\n\nExpected: 500\nReceived: 503")).toBe(false);
  expect(LIMIT_REFUSED_WITH_400.matches("Error: the engine answered \n\nExpected: 500\nReceived: 200")).toBe(false);
});
