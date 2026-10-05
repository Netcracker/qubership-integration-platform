/**
 * The testing service as a target: endpoint mocks, the request matchers that select one, and the call
 * the engine sends them.
 *
 * The routes do not follow the usual verbs (`internal/controllers/controllers.go:90-98`).
 * `POST /endpoint-mocks` is the listing, `POST /endpoint-mocks/create` creates,
 * `POST /endpoint-mocks/{id}` updates, `DELETE /endpoint-mocks` deletes the ids in its body, and the
 * resource has no PUT.
 *
 * The file sits in `api` because nothing here needs the engine or the corpus. A mock is keyed on a
 * `(chainId, elementId)` pair the service never checks against the catalog, so each case invents
 * its own pair with the run token in it, and calls `/endpoint-mocks/call` itself with the
 * `Testing-Service-Context` header the engine would send: base64 of
 * `{chainId, elementId, operationPath, path}`.
 *
 * Field names are the service's own, not the element model's. A response header is `{name, value}`,
 * and a matcher's `entityType` is lowercase. The reference field is `endpointReference`, and a body
 * that spells it `elementReference` is not refused: the unknown field is dropped and the mock is
 * stored with no reference, where no call can reach it.
 *
 * A request matcher reads one of four parts: `body`, `header`, `query_parameter`, or
 * `path_parameter`. The last two read the context header's `path`, aligned against its
 * `operationPath`. The fifth entity type, `status`, reads 0 from every request, and the UI offers it
 * for response rules only. The predicates are the nine of
 * `testing-service/internal/matching/matcher_predicate_factory.go`, not the list-filter conditions
 * in `internal/model/select.go`. Each predicate case sends one request its matcher holds for, which
 * the mock answers, and one it fails for, which the service answers 404 with an empty body.
 *
 * `specs/api/api-prefixes.spec.ts` already pins the proxy's rule for `/endpoint-mocks/call` by
 * status: 404 through nginx, anything else on the service's port. The guard case here adds what the
 * status leaves open. It registers a mock the service would answer, and requires nginx's own 404
 * page for the prefixed path. The un-prefixed `/api/v1/endpoint-mocks/call` answers 404 through
 * the `location /api/` catch-all whatever the guard does, so no case addresses it.
 */
import type { APIResponse } from "@playwright/test";
import { test, expect } from "../../support/fixtures.js";
import { proxyUrl } from "../../env/containers.js";
import { UUID } from "../../support/absent.js";
import { tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";
import {
  TESTING_CONTEXT_HEADER,
  encodeTestingContext,
  inventedReference,
  type EndpointMockReference,
  type RequestMatcher,
  type TestingContext,
  type TestingService,
} from "../../support/testing-service.js";

const CALL = "/api/v1/endpoint-mocks/call";

function contextFor(reference: EndpointMockReference, path = "/", operationPath = "/"): TestingContext {
  return { ...reference, operationPath, path };
}

async function answerOf(response: APIResponse): Promise<{ status: number; body: string }> {
  return { status: response.status(), body: await response.text() };
}

/** What the service answers when no enabled mock on the endpoint matches the call. */
const NO_MOCK = { status: 404, body: "" };

async function removeMocks(testingService: TestingService, ids: readonly string[]): Promise<void> {
  for (const id of ids) await testingService.deleteMock(id).catch(leftBehind(`mock ${id}`));
}

test("an endpoint mock is created, read, listed, updated, and deleted, and the resource has no PUT", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  const reference = inventedReference(run, "mocks-crud");
  const context = contextFor(reference);
  const name = tokenized(run, "mock-crud");
  const matcher: RequestMatcher = { type: "equal", entityType: "header", entityName: "x-e2e-tenant", value: "alpha" };
  const created = await testingService.createMock({
    name,
    reference,
    response: { status: 202, body: "accepted", headers: [{ name: "X-Mocked", value: "crud" }] },
    matchers: [matcher],
  });
  let deleted = false;
  try {
    expect(created.id).toMatch(UUID);
    expect(created).toMatchObject({
      name,
      enabled: true,
      endpointReference: reference,
      responseSettings: { status: 202, delay: 0, message: { body: "accepted", headers: [{ name: "X-Mocked", value: "crud" }] } },
      requestMatchers: [
        { name: "matcher-0", enabled: true, type: "equal", entityType: "header", entityName: "x-e2e-tenant", parameters: [{ name: "value", value: "alpha" }] },
      ],
    });
    expect(created.requestMatchers?.[0].id).toMatch(UUID);
    expect(await testingService.getMock(created.id)).toEqual(created);
    expect((await testingService.mocksOn(reference)).map((each) => each.id)).toEqual([created.id]);

    const tenant = { headers: { "x-e2e-tenant": "alpha" } };
    expect(await answerOf(await testingService.callMock("post", context, tenant))).toEqual({ status: 202, body: "accepted" });

    // The update replaces the matchers and the response whole, and a disabled mock answers nothing.
    const renamed = tokenized(run, "mock-crud-renamed");
    const updated = await testingService.updateMock(created.id, {
      name: renamed,
      reference,
      response: { status: 200, body: "updated" },
      matchers: [{ type: "equal", entityType: "header", entityName: "x-e2e-tenant", value: "beta" }],
    });
    expect(updated.id).toBe(created.id);
    const reread = await testingService.getMock(created.id);
    expect(reread).toMatchObject({ name: renamed, enabled: true, responseSettings: { status: 200, message: { body: "updated" } } });
    expect(reread.requestMatchers?.map((each) => each.parameters)).toEqual([[{ name: "value", value: "beta" }]]);
    expect(await answerOf(await testingService.callMock("post", context, tenant))).toEqual(NO_MOCK);
    const beta = { headers: { "x-e2e-tenant": "beta" } };
    expect(await answerOf(await testingService.callMock("post", context, beta))).toEqual({ status: 200, body: "updated" });

    await testingService.updateMock(created.id, { name: renamed, reference, response: { status: 200, body: "updated" }, enabled: false });
    expect((await testingService.getMock(created.id)).enabled).toBe(false);
    expect(await answerOf(await testingService.callMock("post", context, beta))).toEqual(NO_MOCK);

    expect((await testingService.raw("put", `/api/v1/endpoint-mocks/${created.id}`, { data: {} })).status()).toBe(405);

    expect((await testingService.raw("delete", `/api/v1/endpoint-mocks/${created.id}`)).status()).toBe(204);
    deleted = true;
    const gone = await testingService.raw("get", `/api/v1/endpoint-mocks/${created.id}`);
    expect(gone.status()).toBe(404);
    expect((await gone.json()).errorMessage).toBe(`Endpoint mock ${created.id} not found.`);
    expect(await testingService.mocksOn(reference)).toEqual([]);
  } finally {
    if (!deleted) await removeMocks(testingService, [created.id]);
  }
});

test("a bulk delete removes the endpoint mocks it lists and keeps the rest", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  const reference = inventedReference(run, "mocks-bulk");
  const ids: string[] = [];
  try {
    for (const what of ["bulk-a", "bulk-b", "bulk-kept"]) {
      ids.push((await testingService.createMock({ name: tokenized(run, `mock-${what}`), reference, response: { status: 200, body: what } })).id);
    }
    const [first, second, kept] = ids;
    await testingService.deleteMocks([first, second]);
    expect((await testingService.mocksOn(reference)).map((each) => each.id)).toEqual([kept]);
  } finally {
    await removeMocks(testingService, ids);
  }
});

test("a mock is stored under the service's own field names, and the element model's spellings are refused or dropped", { tag: ["@testing-service", "@tier2"] }, async ({ testingService, run }) => {
  const name = tokenized(run, "mock-fields");
  const reference = inventedReference(run, "mocks-fields");
  const response = { status: 200, delay: 0, message: { body: "fields" } };
  const refused = [
    {
      spelling: "a response header as {key, value}",
      body: { name, enabled: true, endpointReference: reference, responseSettings: { ...response, message: { body: "fields", headers: [{ key: "X-Mocked", value: "yes" }] } } },
      message: 'response header name "" is not an HTTP field name',
    },
    {
      spelling: "an uppercase entity type",
      body: {
        name,
        enabled: true,
        endpointReference: reference,
        requestMatchers: [{ name: "upper", enabled: true, type: "exist", entityType: "HEADER", entityName: "x-e2e-tenant", parameters: [] }],
        responseSettings: response,
      },
      message: 'request matcher "upper": unsupported entity type: HEADER',
    },
  ];
  for (const each of refused) {
    const answer = await testingService.raw("post", "/api/v1/endpoint-mocks/create", { data: each.body });
    expect(answer.status(), each.spelling).toBe(400);
    expect((await answer.json()).errorMessage, each.spelling).toBe(each.message);
  }
  expect(await testingService.mocksNamed(name), "a refused create stored a mock").toEqual([]);

  // `elementReference` is not a field of the mock, so the create succeeds with no reference at all.
  const dropped = await testingService.raw("post", "/api/v1/endpoint-mocks/create", {
    data: { name, enabled: true, elementReference: reference, responseSettings: response },
  });
  expect(dropped.status()).toBe(201);
  const stored = (await dropped.json()) as { id: string; endpointReference: unknown };
  try {
    expect(stored.endpointReference).toBeNull();
    expect(await testingService.mocksOn(reference)).toEqual([]);
    expect(await answerOf(await testingService.callMock("get", contextFor(reference)))).toEqual(NO_MOCK);
  } finally {
    await removeMocks(testingService, [stored.id]);
  }
});

// ---------------------------------------------------------------------------
// Request matchers, one case per predicate
// ---------------------------------------------------------------------------

/** The path template the two parameter getters read the context header's `path` against. */
const OPERATION_PATH = "/orders/{orderId}";

/** One call to the mock endpoint: what the engine would forward, and where it was addressed. */
interface MockCall {
  path?: string;
  headers?: Record<string, string>;
  body?: string;
}

interface PredicateCase {
  predicate: RequestMatcher["type"];
  matcher: Omit<RequestMatcher, "type">;
  holds: MockCall;
  fails: MockCall;
}

// The nine predicates spread over the four parts a request matcher reads, and each case reads its
// part with one call the matcher holds for and one it fails for.
const PREDICATE_CASES: PredicateCase[] = [
  { predicate: "empty", matcher: { entityType: "query_parameter", entityName: "flag" }, holds: { path: "/orders/1?flag=" }, fails: { path: "/orders/1?flag=on" } },
  { predicate: "exist", matcher: { entityType: "header", entityName: "x-e2e-marker" }, holds: { headers: { "x-e2e-marker": "any" } }, fails: {} },
  { predicate: "equal", matcher: { entityType: "path_parameter", entityName: "orderId", value: "42" }, holds: { path: "/orders/42" }, fails: { path: "/orders/43" } },
  { predicate: "contain", matcher: { entityType: "body", value: '"count":2' }, holds: { body: '{"count":2}' }, fails: { body: '{"count":3}' } },
  { predicate: "match", matcher: { entityType: "query_parameter", entityName: "channel", parameters: { pattern: "^w.b$" } }, holds: { path: "/orders/1?channel=web" }, fails: { path: "/orders/1?channel=mobile" } },
  { predicate: "start_with", matcher: { entityType: "path_parameter", entityName: "orderId", value: "ord-" }, holds: { path: "/orders/ord-7" }, fails: { path: "/orders/inv-7" } },
  { predicate: "end_with", matcher: { entityType: "header", entityName: "x-e2e-marker", value: "-probe" }, holds: { headers: { "x-e2e-marker": "e2e-probe" } }, fails: { headers: { "x-e2e-marker": "e2e-other" } } },
  {
    predicate: "match_json_schema",
    matcher: { entityType: "body", parameters: { schema: JSON.stringify({ type: "object", required: ["case"] }) } },
    holds: { body: '{"case":"schema"}' },
    fails: { body: '["case"]' },
  },
  {
    predicate: "match_json",
    matcher: { entityType: "body", parameters: { path: "$.order", sample: '{"id":42}' } },
    holds: { body: '{"order":{"id":42},"extra":true}' },
    fails: { body: '{"order":{"id":43}}' },
  },
];

for (const each of PREDICATE_CASES) {
  test(`the ${each.predicate} request matcher selects the mock for a matching call and not for another`, { tag: ["@testing-service", "@tier2"] }, async ({ testingService, run }) => {
    const reference = inventedReference(run, `mocks-matcher-${each.predicate}`);
    const answer = `matched by ${each.predicate}`;
    const matchers: RequestMatcher[] = [{ type: each.predicate, ...each.matcher }];
    await testingService.withMock({ name: tokenized(run, `mock-${each.predicate}`), reference, response: { status: 200, body: answer }, matchers }, async () => {
      const call = async ({ path = "/orders/1", headers = {}, body = "" }: MockCall) =>
        answerOf(await testingService.callMock("post", contextFor(reference, path, OPERATION_PATH), { headers, data: body }));
      expect(await call(each.holds), "the call the matcher holds for").toEqual({ status: 200, body: answer });
      expect(await call(each.fails), "the call the matcher fails for").toEqual(NO_MOCK);
    });
  });
}

// ---------------------------------------------------------------------------
// Selection, the call endpoint, and the proxy's guard
// ---------------------------------------------------------------------------

test("of the mocks a call matches, the one with the most enabled matchers answers, and the oldest among equals", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  // `compareEndpointMocksByMatcherCountAndThenByCreationTime` in `endpoint_mocks_service.go` orders the
  // candidates by enabled matchers, most first, then by creation time, oldest first.
  const reference = inventedReference(run, "mocks-selection");
  const context = contextFor(reference);
  const ids: string[] = [];
  const register = async (what: string, matchers: RequestMatcher[]) => {
    ids.push((await testingService.createMock({ name: tokenized(run, `mock-${what}`), reference, response: { status: 200, body: what }, matchers })).id);
  };
  try {
    await register("catch-all", []);
    await register("by-header", [{ type: "exist", entityType: "header", entityName: "x-e2e-select" }]);
    // One enabled matcher, like the mock above, plus a disabled one that no call below satisfies.
    await register("by-body", [
      { type: "contain", entityType: "body", value: "select" },
      { type: "exist", entityType: "header", entityName: "x-e2e-never", enabled: false },
    ]);
    const call = async (headers: Record<string, string>, body: string) =>
      (await testingService.callMock("post", context, { headers, data: body })).text();

    expect(await call({ "x-e2e-select": "yes" }, "select"), "three mocks match").toBe("by-header");
    expect(await call({}, "select"), "the header matcher fails").toBe("by-body");
    expect(await call({}, "other"), "only the catch-all matches").toBe("catch-all");
  } finally {
    await removeMocks(testingService, ids);
  }
});

test("a mock answers with the status, headers, and body its response defines", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  const reference = inventedReference(run, "mocks-response");
  const response = {
    status: 418,
    body: '{"teapot":true}',
    headers: [
      { name: "content-type", value: "application/json" },
      { name: "x-e2e-multi", value: "first" },
      { name: "X-E2e-Multi", value: "second" },
    ],
  };
  await testingService.withMock({ name: tokenized(run, "mock-response"), reference, response }, async () => {
    const answer = await testingService.callMock("get", contextFor(reference));
    expect(answer.status()).toBe(418);
    expect(await answer.text()).toBe('{"teapot":true}');
    expect(answer.headers()["content-type"]).toBe("application/json");
    const multi = answer.headersArray().filter((each) => each.name.toLowerCase() === "x-e2e-multi");
    expect(multi.map((each) => each.value)).toEqual(["first", "second"]);
  });
});

test("the call endpoint answers every method a route can carry, and refuses a call without a readable context", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  const reference = inventedReference(run, "mocks-call");
  const context = contextFor(reference);
  const response = { status: 203, body: "mocked", headers: [{ name: "X-Mocked", value: "call" }] };
  await testingService.withMock({ name: tokenized(run, "mock-call"), reference, response }, async () => {
    // `router.All`: the six methods the swagger document lists, and two it does not.
    for (const method of ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"]) {
      const answer = await testingService.callMock(method, context);
      expect(answer.status(), method).toBe(203);
      expect(answer.headers()["x-mocked"], method).toBe("call");
      expect(await answer.text(), method).toBe(method === "HEAD" ? "" : "mocked");

      const bare = await testingService.raw(method, CALL);
      expect(bare.status(), `${method} with no context`).toBe(400);
      if (method !== "HEAD") {
        expect((await bare.json()).errorMessage, method).toBe(`Missing required header: ${TESTING_CONTEXT_HEADER}`);
      }
    }

    const undecodable = await testingService.raw("get", CALL, { headers: { [TESTING_CONTEXT_HEADER]: "%%%" } });
    expect(undecodable.status()).toBe(400);
    expect((await undecodable.json()).errorMessage).toBe(
      `Failed to decode testing context: decode ${TESTING_CONTEXT_HEADER} header: illegal base64 data at input byte 0`,
    );

    // fasthttp refuses a method it does not know before any route is matched.
    const unknown = await testingService.raw("FOO", CALL, { headers: { [TESTING_CONTEXT_HEADER]: encodeTestingContext(context) } });
    expect(await answerOf(unknown)).toEqual({ status: 400, body: "Invalid http method" });

    const elsewhere = contextFor(inventedReference(run, "mocks-call-unmocked"));
    expect(await answerOf(await testingService.callMock("get", elsewhere))).toEqual(NO_MOCK);
  });
});

test("nginx refuses a mock call on the testing service's prefix that the service itself answers", { tag: ["@testing-service", "@infra", "@tier1"] }, async ({ testingService, request, run }) => {
  const reference = inventedReference(run, "mocks-guard");
  const context = contextFor(reference);
  await testingService.withMock({ name: tokenized(run, "mock-guard"), reference, response: { status: 200, body: "mocked" } }, async (mock) => {
    expect(await answerOf(await testingService.callMock("post", context))).toEqual({ status: 200, body: "mocked" });

    // The prefix reaches the service for every other path, so the refusal below is the guard's.
    const prefix = `${proxyUrl()}/api/v1/qip/testing-service`;
    const read = await request.get(`${prefix}/endpoint-mocks/${mock.id}`);
    expect(read.status()).toBe(200);
    expect((await read.json()).id).toBe(mock.id);

    const proxied = await request.post(`${prefix}/endpoint-mocks/call`, {
      headers: { [TESTING_CONTEXT_HEADER]: encodeTestingContext(context) },
    });
    expect(proxied.status()).toBe(404);
    expect(proxied.headers()["server"]).toMatch(/^nginx\b/);
    expect(proxied.headers()["content-type"]).toBe("text/html");
    // nginx's own error page, where the service would answer the mock or an empty 404.
    expect(await proxied.text()).toContain("<h1>404 Not Found</h1>");
  });
});
