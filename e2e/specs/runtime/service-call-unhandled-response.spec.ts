/**
 * A `service-call` response that no handler in `after` matches.
 *
 * The template rethrows a 4xx or 5xx response unless `after` holds an item with `code: "default"`,
 * and a missing `after` counts as an empty one (#1278). Each case calls a seeded chain whose service
 * call a mock answers with 404:
 *
 * - `service-call-no-handlers` has no `after` and declares the migrations up to 108, so the import
 *   runs every later import-file migration on it.
 * - `service-call-default-handler` has the Default handler and nothing else.
 * - `service-call-extension-format` has no `after` and no `migrations` field, the shape of a chain
 *   file the VS Code extension creates. The import treats such a file as current and runs no
 *   migration on it.
 *
 * Before #1278 a call with no `after` rendered no `<choice>`, so the trigger answered with the
 * mock's 404 and body as if the call had succeeded, and only the request attempt was marked failed.
 * A rethrown 404 fails the chain instead: the trigger answers 500 with `CIP-0104`, naming the call.
 *
 * Shown failing: on a stack built from `main` before #1278, the two cases without a Default handler
 * failed on the 500 they expect, receiving 404 and the mock's body. The Default handler case passed.
 */
import type { APIRequestContext } from "@playwright/test";
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { tokenized } from "../../support/run.js";
import { callChain, elementNames, failedElements, HTTP_TRIGGER_STEPS, type RecordedSession, type Sessions } from "../../support/sessions.js";
import type { TestingService } from "../../support/testing-service.js";
import type { Env } from "../../env/index.js";
import { coversBehavior } from "../../registry/covers.js";

const CALL = "Service Call";
const NOT_FOUND = '{"missing":"order"}';

interface CallFixtures {
  request: APIRequestContext;
  env: Env;
  sessions: Sessions;
  testingService: TestingService;
  run: string;
}

interface Answered {
  callId: string;
  status: number;
  body: string;
  session: RecordedSession;
}

/** Calls the seeded chain `fixture` while a mock answers its service call with 404. */
async function callOn404(fixture: string, { request, env, sessions, testingService, run }: CallFixtures): Promise<Answered> {
  const chain = seedChain(readCorpusState(), fixture);
  const callId = chain.elements[CALL];
  return testingService.withMock({ name: tokenized(run, fixture), reference: { chainId: chain.id, elementId: callId }, response: { status: 404, body: NOT_FOUND } }, async () => {
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    const body = await call.response.text();
    return { callId, status: call.response.status(), body, session: await sessions.byExternalId(call.token, { elements: 4 }) };
  });
}

function expectFailed({ callId, status, body, session }: Answered): void {
  expect(status, `the 404 passed through as the chain's answer: ${body}`).toBe(500);
  expect(JSON.parse(body)).toMatchObject({ code: "CIP-0104", extra: { failedElementId: callId } });
  expect(failedElements(session).map((each) => each.elementName)).toEqual([CALL, "Request attempt"]);
}

test.describe("service-call unhandled response", { tag: ["@engine", "@catalog", "@sessions", "@testing-service"] }, () => {
  test("no response handlers fail the chain on a 404", async ({ request, env, sessions, testingService, run }) => {
    coversBehavior("service-call", "unhandled-response");
    expectFailed(await callOn404("service-call-no-handlers", { request, env, sessions, testingService, run }));
  });

  test("a Default handler handles the 404", async ({ request, env, sessions, testingService, run }) => {
    coversBehavior("service-call", "default-handler");
    const { status, body, session } = await callOn404("service-call-default-handler", { request, env, sessions, testingService, run });
    expect(status, body).toBe(404);
    expect(body).toBe(NOT_FOUND);
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, CALL, "Request attempt", "Handle response default"]);
    expect(failedElements(session).map((each) => each.elementName)).toEqual(["Request attempt"]);
  });

  test("an extension-format chain with no after fails on a 404", async ({ request, env, sessions, testingService, run }) => {
    coversBehavior("service-call", "unhandled-response");
    expectFailed(await callOn404("service-call-extension-format", { request, env, sessions, testingService, run }));
  });
});
