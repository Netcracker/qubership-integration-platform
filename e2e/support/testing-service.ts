/**
 * The testing service: the suite's **tool** for endpoint mocks, and a subject of its own.
 *
 * An endpoint mock is how a chain that calls out is made deterministic. The engine's side of it is
 * `EndpointMockTestingService`: while `qip.testing.enabled` is on, an outbound HTTP call made by an
 * exchange carrying the `TESTING_SESSION_ID` property is rewritten to this service's
 * `/api/v1/endpoint-mocks/call`, and the property is set from the `external-session-cip-id` header
 * (`HttpTriggerProcessor.java:85`). So the correlation header is not neutral: it is also the switch
 * that turns mocking on for that exchange.
 *
 * A mock is keyed by `(chainId, elementId)`, where `elementId` is the **design-time** id — the id
 * in the fixture document, not the one a snapshot mints — so a seed fixture's element ids are what
 * a mock's `endpointReference` carries.
 *
 * With no mock matching, the service answers the engine **404**, which is not the same as the call
 * reaching its real endpoint. Only an exchange that never carried the header reaches one.
 */
import { randomUUID } from "node:crypto";
import type { APIRequestContext, APIResponse } from "@playwright/test";
import type { Env } from "../env/index.js";
import { serviceUrl } from "../env/containers.js";
import { noteReached } from "../registry/reached.js";
import { readUntil } from "./poll.js";
import { carriesRunToken, tokenized } from "./run.js";
import { REQUEST_ID_HEADER } from "./sessions.js";
import { leftBehind } from "./teardown.js";
// The correlation header has one definition, in `sessions.ts`, and it is re-exported here because
// this module's contract is about it: the header is also the switch that turns mocking on.
export { CORRELATION_HEADER } from "./sessions.js";

export interface EndpointMockReference {
  chainId: string;
  elementId: string;
}

export interface EndpointMockResponse {
  status: number;
  body: string;
  headers?: Array<{ name: string; value: string }>;
}

/** A condition the request has to meet for a mock to answer it; `entityName` only for a named part. */
export interface RequestMatcher {
  type: "empty" | "exist" | "equal" | "contain" | "match" | "start_with" | "end_with" | "match_json_schema" | "match_json";
  entityType: "body" | "header" | "status" | "query_parameter" | "path_parameter";
  entityName?: string;
  value?: string;
  /** Parameters a predicate reads under another name: `pattern`, `schema`, `sample`, `path`. */
  parameters?: Record<string, string>;
  /** A disabled matcher is stored and never evaluated; the default is enabled. */
  enabled?: boolean;
}

/**
 * Matches a request whose `X-Request-Id` the chain did not set: the header is absent, or it holds
 * the id a proxy on the call's path generated (`Env.generatedRequestId`).
 */
export function noChainRequestId(env: Env): RequestMatcher {
  return env.generatedRequestId === null
    ? { type: "empty", entityType: "header", entityName: REQUEST_ID_HEADER }
    : { type: "match", entityType: "header", entityName: REQUEST_ID_HEADER, parameters: { pattern: env.generatedRequestId } };
}

export interface NamedValue {
  name: string;
  value: string;
}

export interface EndpointMock {
  id: string;
  name: string;
  enabled: boolean;
  endpointReference: EndpointMockReference | null;
}

/** A mock as a create, a read, or an update answers it, with its response and matchers. */
export interface StoredEndpointMock extends EndpointMock {
  responseSettings: {
    status: number;
    delay: number;
    message: { body: string | null; headers: NamedValue[] | null } | null;
  } | null;
  requestMatchers: WireMatcher[] | null;
}

/** What `/endpoint-mocks/call` reads to find the mocks: base64 of this JSON, standard alphabet. */
export interface TestingContext extends EndpointMockReference {
  /** The operation's path template, which a `path_parameter` matcher aligns `path` against. */
  operationPath: string;
  /** The path the call was made to, with the query a `query_parameter` matcher reads. */
  path: string;
}

export const TESTING_CONTEXT_HEADER = "Testing-Service-Context";

export function encodeTestingContext(context: TestingContext): string {
  return Buffer.from(JSON.stringify(context)).toString("base64");
}

/** A condition on the response a test case run gets back; the service stores it as a matcher. */
export type ResponseRule = RequestMatcher & { name: string };

export interface TestCaseRequest {
  name: string;
  enabled: boolean;
  /** The HTTP trigger the case calls: design-time ids, as an endpoint mock's reference. */
  trigger: EndpointMockReference;
  method: string;
  body?: string;
  headers?: NamedValue[];
  queryParameters?: NamedValue[];
  rules?: ResponseRule[];
}

/** What an endpoint mock is created or updated from. */
export interface MockDefinition {
  name: string;
  reference: EndpointMockReference;
  response: EndpointMockResponse;
  matchers?: RequestMatcher[];
  /** The default is enabled. */
  enabled?: boolean;
}

/** The wire shape of a matcher, which a mock's request and a case's response share. */
interface WireMatcher {
  id?: string;
  name: string;
  enabled: boolean;
  type: string;
  entityType: string;
  entityName: string | null;
  parameters: NamedValue[] | null;
}

export interface TestCase {
  id: string;
  name: string;
  enabled: boolean;
  triggerReference: EndpointMockReference;
  requestSettings: {
    method: string;
    timeout: number;
    queryParameters: NamedValue[] | null;
    pathParameters: NamedValue[] | null;
    message: { body: string | null; headers: NamedValue[] | null } | null;
  } | null;
  responseValidationRules: WireMatcher[] | null;
}

/** A test case as a read and the listing answer it, with its rules counted. */
export interface ListedTestCase extends TestCase {
  validationRuleCount: number;
  enabledRuleCount: number;
}

export type RunStatus = "pending" | "running" | "finished" | "canceled" | "skipped";

export interface TestCaseRun {
  id: string;
  testsRunId: string;
  testCaseId: string;
  testCaseName: string;
  chainId: string;
  /** The place of the case in its test run, which the executor runs the cases by. */
  ordinal: number;
  status: RunStatus;
  start: string | null;
  finish: string | null;
  sessionId: string | null;
  errors: number;
}

/**
 * A test run as its view reports it. `status` is the least of its case runs' statuses in
 * alphabetical order, with `pending` read as `running`, so one canceled case reports the whole run
 * `canceled`; `errors` counts the case runs that recorded at least one error.
 */
export interface TestsRun {
  id: string;
  status: RunStatus;
  start: string | null;
  finish: string | null;
  errors: number;
  testCases: number;
}

/** What `POST /tests-runs/create?from=` resolves the ids in its body against. */
export type RunSource = "test_cases" | "tests_runs" | "test_case_runs";

/** Starts a test run the way `TestingService.startRun` does, and records it for deletion. */
export type StartRun = (ids: readonly string[], from?: RunSource) => Promise<string>;

/** A rule that did not hold, or a run that failed before any rule was read, with `matcher` null. */
export interface ValidationError {
  id: string;
  testCaseRunId: string;
  matcherId: string | null;
  matcher: WireMatcher | null;
  message: string;
}

/** One row of an import answer: an archive entry and what the import did with it. */
export interface ImportResult {
  archive: string;
  fileName: string;
  entityId: string | null;
  entityName: string | null;
  result: "created" | "updated" | "error";
  message: string;
}

/** Everything `fetch` takes except the method, which each call names itself. */
type FetchOptions = Omit<NonNullable<Parameters<APIRequestContext["fetch"]>[1]>, "method">;

/** The status a call has to answer: one code, or any 2xx. */
type Expected = number | "ok";

/** A condition of the `filters` body every listing takes. */
interface ListFilter {
  feature: string;
  condition: string;
  values: readonly string[];
}

const TERMINAL_RUN_STATUSES: ReadonlyArray<RunStatus> = ["finished", "canceled", "skipped"];

/** A case run completes in tens of milliseconds against a warm chain; this is the ceiling. */
const RUN_TIMEOUT = 30_000;

/** How long the service waits for a case's call to its trigger, in milliseconds. */
const TRIGGER_TIMEOUT = 30_000;

/** A mock or test case reference no other case, and no other run, uses. */
export function inventedReference(run: string, what: string): EndpointMockReference {
  return { chainId: tokenized(run, what), elementId: randomUUID() };
}

function toWireMatchers(matchers: ResponseRule[]): WireMatcher[] {
  return matchers.map((matcher) => {
    const parameters = Object.entries(matcher.parameters ?? {}).map(([name, value]) => ({ name, value }));
    if (matcher.value !== undefined) parameters.unshift({ name: "value", value: matcher.value });
    return {
      name: matcher.name,
      enabled: matcher.enabled ?? true,
      type: matcher.type,
      entityType: matcher.entityType,
      entityName: matcher.entityName ?? null,
      parameters,
    };
  });
}

function mockBody(mock: MockDefinition): Record<string, unknown> {
  const matchers = mock.matchers ?? [];
  return {
    name: mock.name,
    enabled: mock.enabled ?? true,
    endpointReference: mock.reference,
    requestMatchers: toWireMatchers(matchers.map((matcher, index) => ({ ...matcher, name: `matcher-${index}` }))),
    responseSettings: {
      status: mock.response.status,
      delay: 0,
      message: { body: mock.response.body, headers: mock.response.headers ?? [] },
    },
  };
}

function testCaseBody(request: TestCaseRequest): Record<string, unknown> {
  return {
    name: request.name,
    description: "",
    enabled: request.enabled,
    triggerReference: request.trigger,
    requestSettings: {
      queryParameters: request.queryParameters ?? [],
      pathParameters: [],
      message: { body: request.body ?? null, headers: request.headers ?? [] },
      method: request.method,
      timeout: TRIGGER_TIMEOUT,
    },
    responseValidationRules: toWireMatchers(request.rules ?? []),
  };
}

/**
 * A thin client over the testing service, on its own port through `Env`.
 *
 * It is a **transport** and not just a client: every call goes out through `send` below, which
 * records the row with `noteReached()`, the way the suite's other three transports do:
 * `support/catalog.ts`, `support/sessions.ts` and `support/engine.ts`. A spec asserting this
 * service's status or failure body calls `raw` rather than its own `request` fixture, the way it
 * does with `Catalog.raw`, so the call is recorded against the row either way.
 */
export class TestingService {
  // Plain fields rather than parameter properties, for the same reason `Catalog` and `Sessions` use
  // them: `node --experimental-strip-types` rejects those, and the after-the-run steps load this
  // module outside Playwright.
  private readonly api: APIRequestContext;
  private readonly base: string;

  constructor(api: APIRequestContext, env: Env) {
    this.api = api;
    this.base = env.url("testing-service");
  }

  // -------------------------------------------------------------------------
  // Transport
  // -------------------------------------------------------------------------

  private async send(method: string, path: string, options: FetchOptions = {}): Promise<APIResponse> {
    const url = `${this.base}${path}`;
    noteReached("testing-service", method, url);
    return await this.api.fetch(url, { method: method.toUpperCase(), ...options });
  }

  /** Sends the call and throws, naming the answer, unless it answered `expected`. */
  private async call(method: string, path: string, expected: Expected, options: FetchOptions = {}): Promise<APIResponse> {
    const response = await this.send(method, path, options);
    if (expected === "ok" ? !response.ok() : response.status() !== expected) {
      throw new Error(`${method.toUpperCase()} ${this.base}${path} answered ${response.status()}: ${await response.text()}`);
    }
    return response;
  }

  private async read<T>(method: string, path: string, options: FetchOptions = {}, expected: Expected = "ok"): Promise<T> {
    return (await (await this.call(method, path, expected, options)).json()) as T;
  }

  /** A listing: every one is a `POST` of `{filters}`, and an empty one answers `null`. */
  private async list<T>(path: string, filters: ListFilter[]): Promise<T[]> {
    return (await this.read<T[] | null>("post", path, { data: { filters } })) ?? [];
  }

  /**
   * The raw response, for a spec asserting a status or a failure body.
   *
   * The record is taken at the send rather than at the answer, which is what makes this usable for
   * a call the service is meant to refuse: `reconcile()` reads the annotations of passing tests
   * only, so a call that never answered is dropped by the reader instead of by the transport.
   */
  raw(method: string, path: string, options: FetchOptions = {}): Promise<APIResponse> {
    return this.send(method, path, options);
  }

  // -------------------------------------------------------------------------
  // Endpoint mocks
  // -------------------------------------------------------------------------

  /**
   * Registers a mock and answers with it.
   *
   * No matchers means every request to that element matches, which is what a smoke check wants: a
   * matcher the service cannot evaluate is skipped rather than failing the call, so a mock with
   * matchers can silently not apply.
   */
  createMock(mock: MockDefinition): Promise<StoredEndpointMock> {
    return this.read("post", "/api/v1/endpoint-mocks/create", { data: mockBody(mock) }, 201);
  }

  /**
   * Runs `body` with a mock registered, then deletes it. A failed delete is logged, not thrown, so
   * it cannot replace what the body failed on; the run-token sweep removes the mock either way.
   */
  async withMock<T>(definition: MockDefinition, body: (mock: StoredEndpointMock) => Promise<T>): Promise<T> {
    const mock = await this.createMock(definition);
    try {
      return await body(mock);
    } finally {
      await this.deleteMock(mock.id).catch(leftBehind(`mock ${definition.name}`));
    }
  }

  /** `POST /endpoint-mocks/{id}` is the update, and it replaces the matchers and the response whole. */
  updateMock(id: string, mock: MockDefinition): Promise<StoredEndpointMock> {
    return this.read("post", `/api/v1/endpoint-mocks/${id}`, { data: mockBody(mock) });
  }

  getMock(id: string): Promise<StoredEndpointMock> {
    return this.read("get", `/api/v1/endpoint-mocks/${id}`);
  }

  async deleteMock(id: string): Promise<void> {
    await this.call("delete", `/api/v1/endpoint-mocks/${id}`, 204);
  }

  /** The bulk delete, which takes the ids in the body of `DELETE /endpoint-mocks`. */
  async deleteMocks(ids: readonly string[]): Promise<void> {
    await this.call("delete", "/api/v1/endpoint-mocks", 204, { data: ids });
  }

  /** The mock listing, which is `POST /endpoint-mocks`, narrowed to the names holding `fragment`. */
  mocksNamed(fragment: string): Promise<EndpointMock[]> {
    return this.list("/api/v1/endpoint-mocks", [{ feature: "name", condition: "contains", values: [fragment] }]);
  }

  /**
   * The mocks registered on one element, which is how a spec confirms its own is gone.
   *
   * A broken listing throws rather than answering an empty list. "The mock outlived its own delete"
   * is asserted by reading this, so an empty list on a failed listing turns the one condition the
   * assertion exists to catch into a pass.
   */
  mocksOn(reference: EndpointMockReference): Promise<EndpointMock[]> {
    return this.list("/api/v1/endpoint-mocks", [
      { feature: "chain_id", condition: "is", values: [reference.chainId] },
      { feature: "element_id", condition: "is", values: [reference.elementId] },
    ]);
  }

  /** Calls the mocks the way the engine does, with the context encoded into its header. */
  callMock(method: string, context: TestingContext, options: FetchOptions = {}): Promise<APIResponse> {
    return this.send(method, "/api/v1/endpoint-mocks/call", {
      ...options,
      headers: { ...options.headers, [TESTING_CONTEXT_HEADER]: encodeTestingContext(context) },
    });
  }

  // -------------------------------------------------------------------------
  // Test cases
  // -------------------------------------------------------------------------

  createTestCase(request: TestCaseRequest): Promise<TestCase> {
    return this.read("post", "/api/v1/test-cases/create", { data: testCaseBody(request) }, 201);
  }

  /**
   * Creates the test cases and hands them to `body` with a `start` that records each test run it
   * starts, then deletes those test runs and the cases. Each id is recorded as soon as it exists, so
   * a failure halfway through still removes what was created; a failed delete is logged rather than
   * thrown.
   */
  async withTestCases<T>(
    requests: readonly TestCaseRequest[],
    body: (cases: TestCase[], start: StartRun) => Promise<T>,
  ): Promise<T> {
    const cases: TestCase[] = [];
    const runs: string[] = [];
    const start: StartRun = async (ids, from) => {
      const runId = await this.startRun(ids, from);
      runs.push(runId);
      return runId;
    };
    try {
      for (const request of requests) cases.push(await this.createTestCase(request));
      return await body(cases, start);
    } finally {
      for (const id of runs) await this.deleteTestsRun(id).catch(leftBehind(`test run ${id}`));
      for (const each of cases) await this.deleteTestCase(each.id).catch(leftBehind(`test case ${each.name}`));
    }
  }

  /** `POST /test-cases/{id}` is the update; the service has no PUT. */
  updateTestCase(id: string, request: TestCaseRequest): Promise<TestCase> {
    return this.read("post", `/api/v1/test-cases/${id}`, { data: testCaseBody(request) });
  }

  /** The read answers the listing's view of the case, with the rule counts. */
  getTestCase(id: string): Promise<ListedTestCase> {
    return this.read("get", `/api/v1/test-cases/${id}`);
  }

  /** The listing, which is `POST /test-cases`, narrowed to the names holding `fragment`. */
  testCasesNamed(fragment: string): Promise<ListedTestCase[]> {
    return this.list("/api/v1/test-cases", [{ feature: "name", condition: "contains", values: [fragment] }]);
  }

  async deleteTestCase(id: string): Promise<void> {
    await this.call("delete", `/api/v1/test-cases/${id}`, 204);
  }

  /** The bulk delete, which takes the ids in the body of `DELETE /test-cases`. */
  async deleteTestCases(ids: readonly string[]): Promise<void> {
    await this.call("delete", "/api/v1/test-cases", 204, { data: ids });
  }

  // -------------------------------------------------------------------------
  // Runs
  // -------------------------------------------------------------------------

  /**
   * Runs one test case to the end through `start` and returns its case run with the errors the run
   * recorded.
   */
  async runOnce(start: StartRun, testCaseId: string): Promise<{ caseRun: TestCaseRun; errors: ValidationError[] }> {
    const runId = await start([testCaseId]);
    const [caseRun] = await this.awaitCaseRuns(runId, 1);
    return { caseRun, errors: await this.errorsOf(caseRun.id) };
  }

  /** Queues a test run over the test cases `ids` resolve to under `from`, and returns its id. */
  startRun(ids: readonly string[], from: RunSource = "test_cases"): Promise<string> {
    return this.read("post", `/api/v1/tests-runs/create?from=${from}`, { data: ids }, 201);
  }

  /**
   * The case runs of a test run once `settled` holds for them, by default once `expected` of them
   * have stopped, or an error carrying the last reading when that has not happened by `timeout`.
   */
  async awaitCaseRuns(
    testsRunId: string,
    expected: number,
    settled = (runs: TestCaseRun[]) =>
      runs.length === expected && runs.every((each) => TERMINAL_RUN_STATUSES.includes(each.status)),
    timeout = RUN_TIMEOUT,
  ): Promise<TestCaseRun[]> {
    const runs = await readUntil(() => this.caseRunsOf(testsRunId), settled, timeout, 200);
    if (!settled(runs)) {
      throw new Error(`test run ${testsRunId} did not settle ${expected} case run(s) in ${timeout} ms: ${JSON.stringify(runs)}`);
    }
    return runs;
  }

  /** The validation errors of one case run, with the matcher each was recorded against unless told otherwise. */
  async errorsOf(testCaseRunId: string, withMatchers = true): Promise<ValidationError[]> {
    const path = `/api/v1/test-case-runs/${testCaseRunId}/errors?withMatchers=${withMatchers}`;
    return (await this.read<ValidationError[] | null>("get", path)) ?? [];
  }

  /** The case runs of one test run, each with the session id its call to the trigger carried. */
  caseRunsOf(testsRunId: string): Promise<TestCaseRun[]> {
    return this.list("/api/v1/test-case-runs", [{ feature: "tests_run_id", condition: "is", values: [testsRunId] }]);
  }

  getTestsRun(id: string): Promise<TestsRun> {
    return this.read("get", `/api/v1/tests-runs/${id}`);
  }

  /** The test run listing, which is `POST /tests-runs`, narrowed to `ids`. */
  testsRunsWithIds(ids: readonly string[]): Promise<TestsRun[]> {
    return this.list("/api/v1/tests-runs", [{ feature: "id", condition: "in", values: ids }]);
  }

  getCaseRun(id: string): Promise<TestCaseRun> {
    return this.read("get", `/api/v1/test-case-runs/${id}`);
  }

  /** Cancels a case run that is still pending; one already claimed runs to the end. */
  async cancelCaseRun(id: string): Promise<void> {
    await this.call("post", `/api/v1/test-case-runs/${id}/cancel`, 204);
  }

  async cancelCaseRuns(ids: readonly string[]): Promise<void> {
    await this.call("post", "/api/v1/test-case-runs/cancel", 204, { data: ids });
  }

  /** Cancels the case runs of a test run that are still pending. */
  async cancelTestsRun(id: string): Promise<void> {
    await this.call("post", `/api/v1/tests-runs/${id}/cancel`, 204);
  }

  async cancelTestsRuns(ids: readonly string[]): Promise<void> {
    await this.call("post", "/api/v1/tests-runs/cancel", 204, { data: ids });
  }

  /** Deletes a test run with its case runs and their validation errors, which the schema cascades. */
  async deleteTestsRun(id: string): Promise<void> {
    await this.call("delete", `/api/v1/tests-runs/${id}`, 204);
  }

  /** The bulk delete, which takes the ids in the body of `DELETE /tests-runs`. */
  async deleteTestsRuns(ids: readonly string[]): Promise<void> {
    await this.call("delete", "/api/v1/tests-runs", 204, { data: ids });
  }

  // -------------------------------------------------------------------------
  // Exports and imports
  // -------------------------------------------------------------------------

  /** The CSV of one test run's case runs, one row per validation error, as records. */
  exportTestsRun(id: string): Promise<string[][]> {
    return this.csv(`/api/v1/tests-runs/${id}/export`);
  }

  exportTestsRuns(ids: readonly string[]): Promise<string[][]> {
    return this.csv("/api/v1/tests-runs/export", ids);
  }

  exportCaseRun(id: string): Promise<string[][]> {
    return this.csv(`/api/v1/test-case-runs/${id}/export`);
  }

  exportCaseRuns(ids: readonly string[]): Promise<string[][]> {
    return this.csv("/api/v1/test-case-runs/export", ids);
  }

  /** The CSV of validation errors by their own ids, as records. */
  exportErrors(ids: readonly string[]): Promise<string[][]> {
    return this.csv("/api/v1/test-case-runs/errors/export", ids);
  }

  /** The test cases `ids` name, as the ZIP `POST /test-cases/export` answers. */
  exportTestCases(ids: readonly string[]): Promise<Buffer> {
    return this.zip("/api/v1/test-cases/export", ids);
  }

  exportMocks(ids: readonly string[]): Promise<Buffer> {
    return this.zip("/api/v1/endpoint-mocks/export", ids);
  }

  /** One result per archive entry; a refused entry is a row with `result: "error"`, not a failed call. */
  importTestCases(archive: Buffer, name = "test-cases.zip"): Promise<ImportResult[]> {
    return this.read("post", "/api/v1/test-cases/import", {
      multipart: { file: { name, mimeType: "application/zip", buffer: archive } },
    });
  }

  importMocks(archive: Buffer, name = "endpoint-mocks.zip"): Promise<ImportResult[]> {
    return this.read("post", "/api/v1/endpoint-mocks/import", {
      multipart: { file: { name, mimeType: "application/zip", buffer: archive } },
    });
  }

  private async zip(path: string, ids: readonly string[]): Promise<Buffer> {
    return Buffer.from(await (await this.export(path, "application/zip", ids)).body());
  }

  private async csv(path: string, ids?: readonly string[]): Promise<string[][]> {
    return parseCsv(await (await this.export(path, "text/csv", ids)).text());
  }

  /** Every export is a POST, and the bulk ones take the ids in the body; throws unless it answered `contentType`. */
  private async export(path: string, contentType: string, ids?: readonly string[]): Promise<APIResponse> {
    const response = await this.call("post", path, 200, ids === undefined ? {} : { data: ids });
    const answered = response.headers()["content-type"] ?? "";
    if (!answered.startsWith(contentType)) throw new Error(`POST ${this.base}${path} answered ${answered}: ${await response.text()}`);
    return response;
  }
}

/**
 * The records of an RFC 4180 document, as Go's `encoding/csv` writes one: a field is quoted when it
 * holds a comma, a quote or a line break, and a quote inside it is doubled. The empty record every
 * export ends with is dropped.
 */
function parseCsv(text: string): string[][] {
  const records: string[][] = [];
  let record: string[] = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        field += '"';
        index++;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      record.push(field);
      field = "";
    } else if (char === "\n") {
      record.push(field);
      records.push(record);
      record = [];
      field = "";
    } else {
      field += char;
    }
  }
  if (field !== "" || record.length > 0) records.push([...record, field]);
  return records.filter((each) => !(each.length === 1 && each[0] === ""));
}

// ---------------------------------------------------------------------------
// The sweep's side of the same service
// ---------------------------------------------------------------------------

/**
 * The testing service on its own port, for the sweep.
 *
 * The sweep runs in `globalSetup` and `globalTeardown`, where there is no `Env` and no worker
 * fixture to take a client from, so the two functions below reach the stack over `fetch` the way
 * `waitForRoutes` in `support/corpus.ts` does. `TestingService` above is the spec-side half and is
 * reached through the `testingService` worker fixture, beside `catalog`, `engine` and `sessions`.
 */
function testingServiceUrl(): string {
  return serviceUrl("testing-service");
}

/**
 * How many mocks one listing asks for, which is not how many it gets.
 *
 * The service serves a page and clamps the request to it: `PaginationLimit` is 20 by default
 * (`internal/config/config.go:21`), and `effectiveLimit` in `internal/dao/pagination.go` answers
 * the cap both for a request above it and for one that asks for nothing. So this number is a
 * ceiling and never a way past the cap — an installation configured to serve more is read in fewer
 * calls, and a default one answers 20 whatever this says.
 */
const MOCK_PAGE = 100;

/**
 * The mocks this run named, read to the end of the listing.
 *
 * A mock is keyed on `(chainId, elementId)`, both of which a fixture freezes, so an id says nothing
 * about which run created it and the name is the only handle the sweep has. The filter narrows the
 * listing server-side and `carriesRunToken` decides: the token's spelling has one definition, in
 * `run.ts`, and a second one here would drift from it silently.
 *
 * The answer says nothing about what it left out — a bare JSON array, no total and no next-page
 * marker — so a truncated page and a last one look alike, and reading until a page comes back empty
 * is the only ending the wire carries. The window is ordered on `id` because a `LIMIT`/`OFFSET`
 * window over an unordered query can repeat one row and skip another. The service does have one
 * unpaginated answer — `return_ids=true`, which drops the pagination in `findAll`
 * (`internal/controllers/response.go`) — and it answers ids alone, while the name is the field this
 * reading decides on.
 *
 * A run creates more mocks than one page holds, so stopping at the first page would be the silent
 * form of the failure the sweep exists to prevent: the sweep would delete the page it saw, report
 * success, and `forgetRun` would then drop the manifest entry that is the only handle left on the
 * rest.
 */
export async function mocksOfRun(
  run: string,
  base: string = testingServiceUrl(),
): Promise<EndpointMock[]> {
  const listing = `${base}/api/v1/endpoint-mocks?sort_by=id&sort_order=ASC&limit=${MOCK_PAGE}`;
  const named: EndpointMock[] = [];
  let offset = 0;
  for (;;) {
    const url = `${listing}&offset=${offset}`;
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        filters: [{ feature: "name", condition: "contains", values: [`e2e-${run}`] }],
      }),
    });
    if (!response.ok) {
      throw new Error(`POST ${url} answered ${response.status}: ${await response.text()}`);
    }
    const page = ((await response.json()) as EndpointMock[] | null) ?? [];
    if (page.length === 0) return named;
    named.push(...page.filter((mock) => carriesRunToken(mock.name, run)));
    offset += page.length;
  }
}

/**
 * Removes one mock, and throws when it is still there.
 *
 * One at a time rather than through the bulk delete, for the reason the sweep deletes one chain at
 * a time: an answer covering a set says nothing about which member of it survived, and a mock that
 * survives goes on intercepting the sender it is keyed on for every later run.
 */
export async function removeMock(id: string, base: string = testingServiceUrl()): Promise<void> {
  const url = `${base}/api/v1/endpoint-mocks/${id}`;
  const response = await fetch(url, { method: "DELETE" });
  if (!response.ok) {
    throw new Error(`DELETE ${url} answered ${response.status}: ${await response.text()}`);
  }
}
