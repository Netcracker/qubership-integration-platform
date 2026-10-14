/**
 * What the four services answer when a caller asks for something that is not there, or sends a body
 * that is not JSON.
 *
 * Every controller in the platform reaches an error handler on some path, and nothing pinned what
 * that handler produces. A regression in it does not fail a test — it reaches the UI as "undefined"
 * in a notification, and the suite stays green, because every other spec asserts the happy path and
 * reads a failure only as `expect(response.ok()).toBe(true)`.
 *
 * Measured, and the reason this is a table rather than one assertion: there are **three** shapes,
 * not one.
 *
 * - The application handler's own shape, `application/json`, on every service. The catalog's is
 *   `{serviceName, errorMessage, errorDate}`; the engine, sessions-management and the testing
 *   service add `stacktrace`, whose value on this stack is the literal string
 *   `"No Stacktrace Available"`. The `serviceName` is a display name and differs from the role:
 *   `Catalog`, `Engine`, `Session Management`, `testing-service`.
 * - Spring's RFC 9457 shape, `application/problem+json`, `{type, title, status, detail, instance}`,
 *   whenever the framework rejects the request before a controller sees it — an unreadable body, a
 *   parameter that will not convert, a method that is not mapped.
 * - The testing service has no second shape. Fiber does not have Spring's framework layer, so the
 *   malformed body that answers `problem+json` on the three Java services answers the Go service's
 *   own `{serviceName, errorMessage, stacktrace, errorDate}` with `serviceName: "testing-service"`.
 *
 * The engine's row is a malformed *parameter* rather than a malformed body, and that is measured
 * rather than lazy: none of its six operations reads a body a caller can break. The two retry
 * operations declare one in `/v3/api-docs`, but the chain lookup runs first, so a truncated body on
 * an unknown chain answers the 404 and never reaches the reader. `limit=not-a-number` reaches the
 * same Spring handler the other services' unreadable bodies do.
 *
 * The six probes against the three Java services claim no rows in the operation registry: they pin
 * the error handler, not the operations they happen to call, and the registry's rule is that a spec
 * merely touching an endpoint another spec covers leaves that row where it is. All six of those
 * rows are already covered by the spec that owns each one.
 *
 * The testing service is the exception, and it is why its two probes go through `TestingService`
 * rather than through the raw `request` fixture. Nothing else in the suite calls
 * `GET /api/v1/test-cases/{id}` or `POST /api/v1/test-cases`, so leaving them on the fixture left
 * two rows at `not-reached` while these cases asserted their whole
 * failure contract on every run — status, `content-type`, the complete key set, `serviceName`,
 * `errorMessage` and the `errorDate` format — which is what the registry calls `covered`. A
 * transport is what lets `reconcile()` say so in both directions instead of a reader having to
 * notice. The malformed bodies are unchanged by the move: `TestingService.raw` takes the same
 * options `request.fetch` does, so "send something the service cannot read" stays expressible.
 */
import { test, expect } from "../../support/fixtures.js";
import type { APIRequestContext, APIResponse } from "@playwright/test";
import type { ServiceRole } from "../../env/index.js";
import { ABSENT_ID, ABSENT_UUID } from "../../support/absent.js";
import { notTheKnownDefect } from "../../support/known-defect.js";
import { TestingService } from "../../support/testing-service.js";
import { COMPONENT_TAG } from "./constants.js";

/** The catalog's application shape. It is the one service whose handler omits `stacktrace`. */
const APP_SHAPE = ["errorDate", "errorMessage", "serviceName"];

/** The same handler on the other three, which carry the field whether or not it holds a trace. */
const APP_SHAPE_WITH_TRACE = ["errorDate", "errorMessage", "serviceName", "stacktrace"];

/** RFC 9457, which Spring produces for anything rejected before a controller is entered. */
const PROBLEM_SHAPE = ["detail", "instance", "status", "title", "type"];

const BROKEN_JSON = '{"name":';

interface Probe {
  role: ServiceRole;
  /** What the request is, for the test title. */
  what: string;
  /**
   * The call. `testing` is the recording transport, and the testing-service probes take it rather
   * than `request` so the rows they assert are derived by the run instead of typed into the
   * registry; the other probes address rows another spec already owns and ignore it.
   */
  send: (request: APIRequestContext, base: string, testing: TestingService) => Promise<APIResponse>;
  status: number;
  contentType: string;
  fields: string[];
  /** Present only on the application shape; the problem shape carries no service identity at all. */
  serviceName?: string;
  /** A substring of `errorMessage` or `detail`, so a handler that stops saying what went wrong fails. */
  says: string;
}

const PROBES: Probe[] = [
  {
    role: "runtime-catalog",
    what: "an unknown chain",
    send: (request, base) => request.get(`${base}/v1/chains/${ABSENT_ID}`, { failOnStatusCode: false }),
    status: 404,
    contentType: "application/json",
    fields: APP_SHAPE,
    serviceName: "Catalog",
    says: `Can't find chain with id: ${ABSENT_ID}`,
  },
  {
    role: "runtime-catalog",
    what: "a truncated body",
    send: (request, base) =>
      request.post(`${base}/v1/chains`, {
        headers: { "content-type": "application/json" },
        data: BROKEN_JSON,
        failOnStatusCode: false,
      }),
    status: 400,
    contentType: "application/problem+json",
    fields: PROBLEM_SHAPE,
    says: "Failed to read request",
  },
  {
    role: "engine",
    what: "an unknown deployment",
    send: (request, base) =>
      request.delete(`${base}/v1/engine/live-exchanges/${ABSENT_ID}/${ABSENT_ID}`, {
        failOnStatusCode: false,
      }),
    status: 404,
    contentType: "application/json",
    fields: APP_SHAPE_WITH_TRACE,
    serviceName: "Engine",
    says: `No deployment found for id ${ABSENT_ID}`,
  },
  {
    role: "engine",
    what: "a parameter that will not convert",
    send: (request, base) =>
      request.get(`${base}/v1/engine/live-exchanges?limit=not-a-number`, {
        failOnStatusCode: false,
      }),
    status: 400,
    contentType: "application/problem+json",
    fields: PROBLEM_SHAPE,
    says: "Failed to convert 'limit'",
  },
  {
    role: "sessions-management",
    what: "an unknown session",
    send: (request, base) =>
      request.get(`${base}/v1/sessions/${ABSENT_ID}`, { failOnStatusCode: false }),
    status: 404,
    contentType: "application/json",
    fields: APP_SHAPE_WITH_TRACE,
    serviceName: "Session Management",
    says: `Can't find session ${ABSENT_ID}`,
  },
  {
    role: "sessions-management",
    what: "a truncated body",
    send: (request, base) =>
      request.post(`${base}/v1/sessions`, {
        headers: { "content-type": "application/json" },
        data: BROKEN_JSON,
        failOnStatusCode: false,
      }),
    status: 400,
    contentType: "application/problem+json",
    fields: PROBLEM_SHAPE,
    says: "Failed to read request",
  },
  {
    role: "testing-service",
    what: "an unknown test case",
    send: (_request, _base, testing) => testing.raw("get", `/api/v1/test-cases/${ABSENT_UUID}`),
    status: 404,
    contentType: "application/json",
    fields: APP_SHAPE_WITH_TRACE,
    serviceName: "testing-service",
    says: `Test case ${ABSENT_UUID} not found.`,
  },
  {
    role: "testing-service",
    what: "a truncated body",
    send: (_request, _base, testing) =>
      testing.raw("post", "/api/v1/test-cases", {
        headers: { "content-type": "application/json" },
        data: BROKEN_JSON,
      }),
    status: 400,
    // No problem+json here: the Go service answers its own shape on the framework's path too.
    contentType: "application/json",
    fields: APP_SHAPE_WITH_TRACE,
    serviceName: "testing-service",
    says: "Malformed request body",
  },
];

for (const probe of PROBES) {
  test(`${probe.role} answers ${probe.what} with a pinned error body`, { tag: [COMPONENT_TAG[probe.role], "@tier2"] }, async ({ request, env, testingService }) => {
    const response = await probe.send(request, env.url(probe.role), testingService);

    expect(response.status()).toBe(probe.status);
    expect(response.headers()["content-type"]).toContain(probe.contentType);

    const body = (await response.json()) as Record<string, unknown>;
    // The whole field set, not a subset: an added field is a contract change the UI has to be told
    // about, and toMatchObject would let one through.
    expect(Object.keys(body).sort()).toEqual(probe.fields);

    if (probe.serviceName !== undefined) {
      expect(body.serviceName).toBe(probe.serviceName);
      expect(String(body.errorMessage)).toContain(probe.says);
      // A timestamp the UI renders. Pinning the format rather than the value, because an ISO
      // instant and this space-separated local one are not the same thing to a `Date` constructor.
      expect(String(body.errorDate)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+$/);
    } else {
      expect(body.status).toBe(probe.status);
      expect(String(body.detail)).toContain(probe.says);
      expect(body.type).toBe("about:blank");
    }
  });
}

/**
 * The mutation check for the table above.
 *
 * A spec that asserted "some 4xx with some JSON" would pass on any of these answers, so the
 * mutation has to produce a different 4xx: an `Accept` the handler cannot negotiate. Measured, the
 * catalog and the engine answer **406 `application/problem+json`** — a status the table does not
 * hold and a shape the application rows do not have, so both halves of the pin are load-bearing.
 */
for (const role of ["runtime-catalog", "engine"] as const) {
  test(`${role} answers an unnegotiable Accept differently from a not-found`, { tag: [COMPONENT_TAG[role], "@tier2"] }, async ({ request, env }) => {
    const path =
      role === "runtime-catalog"
        ? `/v1/chains/${ABSENT_ID}`
        : `/v1/engine/live-exchanges/${ABSENT_ID}/${ABSENT_ID}`;
    const response = await request.fetch(`${env.url(role)}${path}`, {
      method: role === "runtime-catalog" ? "GET" : "DELETE",
      headers: { accept: "application/xml" },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(406);
    expect(response.headers()["content-type"]).toContain("application/problem+json");
    expect(Object.keys((await response.json()) as Record<string, unknown>).sort()).toEqual(
      PROBLEM_SHAPE,
    );
  });
}

/**
 * The same mutation against sessions-management, which does not survive it.
 *
 * `GlobalExceptionHandler#sessionsNotFoundExceptionHandler` produces a body it can only write as
 * JSON, and with `Accept: application/xml` the resolver throws
 * `HttpMediaTypeNotAcceptableException` **inside** the handler. The 404 becomes a **500 with an
 * empty body** and the container logs the original exception as an unhandled one, where the catalog
 * and the engine answer a clean 406.
 *
 * Carried as `test.fail()` rather than as an assertion that 500 is the contract: pinning the
 * measured 500 would make the fix look like a regression. This turns red the day the handler
 * negotiates, which is the signal to delete the annotation.
 *
 * `test.fail()` accepts *any* failure, so the body narrows itself to the one it carries: with
 * `failOnStatusCode: false` a bare `expect(...).toBe(406)` is failed just as readily by a service
 * that never answered, and the annotation would report that green. `notTheKnownDefect` in
 * `support/known-defect.ts` is that narrowing, shared with the other case that carries one.
 *
 * The status alone does not identify the divergence either. What the container falls back to is a
 * 500 with a **zero-length** body, and a 500 carrying one is some other failure — a handler that
 * did render something, or a service broken for a reason unrelated to negotiation. The emptiness is
 * asserted with the status.
 */
test.fail("sessions-management negotiates an Accept it cannot write", { tag: [COMPONENT_TAG["sessions-management"], "@tier2"] }, async ({ request, env }) => {
  const url = `${env.url("sessions-management")}/v1/sessions/${ABSENT_ID}`;
  const response = await request
    .get(url, { headers: { accept: "application/xml" }, failOnStatusCode: false })
    .catch(() => null);
  if (!response) {
    notTheKnownDefect(`${url} did not answer at all, so nothing about content negotiation was read`);
  }

  const status = response.status();
  const body = await response.text();
  const isTheFallback = status === 500 && body.length === 0;
  if (!isTheFallback && status !== 406) {
    notTheKnownDefect(
      `${url} answered ${status} with ${body.length} bytes, which is neither the empty 500 this ` +
        `handler produces nor the 406 the catalog and the engine answer. The annotation carries ` +
        `one divergence and this is not it`,
    );
  }
  expect(status, "the handler now negotiates: delete the test.fail() annotation").toBe(406);
});

/**
 * The testing service is the reason the mutation above is not one loop over four services.
 *
 * Fiber does no content negotiation, so `Accept: application/xml` changes nothing and the answer is
 * byte-for-byte the not-found contract. What discriminates its rows instead is the status: the same
 * endpoint with an id that is not a UUID answers **400** with the identical field set, so a spec
 * asserting only the shape would pass on the wrong failure.
 */
test("the testing service ignores Accept, so its status is what discriminates", { tag: [COMPONENT_TAG["testing-service"], "@tier2"] }, async ({ testingService }) => {
  const negotiated = await testingService.raw("get", `/api/v1/test-cases/${ABSENT_UUID}`, {
    headers: { accept: "application/xml" },
  });
  expect(negotiated.status()).toBe(404);
  expect(negotiated.headers()["content-type"]).toContain("application/json");

  const malformed = await testingService.raw("get", "/api/v1/test-cases/not-a-uuid");
  expect(malformed.status()).toBe(400);
  const body = (await malformed.json()) as Record<string, unknown>;
  expect(Object.keys(body).sort()).toEqual(APP_SHAPE_WITH_TRACE);
  expect(String(body.errorMessage)).toContain("invalid UUID length");
});
