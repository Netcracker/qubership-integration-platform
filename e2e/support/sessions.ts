/**
 * Sessions-management: correlating a chain call with the trace the engine recorded, reading that
 * trace, and the rest of the module's surface — search, export, import, and the five deletes.
 *
 * It is one of the suite's four transports, beside `support/catalog.ts`, `support/engine.ts` and
 * `support/testing-service.ts`, and the reason it is a transport rather than a helper is
 * `noteReached()`: a call made through the
 * raw `request` fixture records nothing against `registry/operations.ts`, so the row it touched
 * stays unverifiable in both directions.
 *
 * The lookup half came first, and its own notes follow.
 *
 * One lookup, keyed on the correlation token the call sent. Two things shape it:
 *
 * - The poll tolerates a non-2xx, because `GET /v1/sessions/external-id/{token}` answers **404 with
 *   an error body** until the session is written, not 200 with an empty one.
 * - The token is an argument rather than always minted here, so a spec comparing two sides of one
 *   scenario can correlate both on the same call. `callChain` mints one when the caller has no
 *   opinion and takes the caller's when it does.
 *
 * The measured facts this module encodes, each of which cost a spec somewhere:
 *
 * - **`includeDetails` defaults to `false`**, and without it `sessionElements` comes back present
 *   and **`null`**. So a reader that tests key presence reads a detail-free session as a detailed
 *   one with no elements; `hasTrace` tests `!== null`.
 * - **Elements nest through `children[]`.** Measured on the `http-echo` fixture: the top level is
 *   `HTTP Trigger` and `Header Modification`, and `Validate Request` sits one level below the
 *   trigger. A spec looking only at the top level cannot see which branch an exchange took.
 * - **`camelName` is the element type and repeats** — `http-trigger` appears twice in that trace,
 *   once for the synthetic `Validate Request`. `elementName` is the instance name the fixture
 *   chose, and it is what a spec asserts on.
 * - **A session is queryable roughly 1-2 s after the chain answers 200**, so the poll is bounded
 *   well above that. A budget under ~2 s flakes on its own.
 * - **An unused token and a session that has not landed yet answer the same 404.** Nothing in the
 *   response separates them, so the helper separates them the only way left: it bounds the wait and
 *   names the token in the failure, so a spec that correlated on the wrong header says so.
 *
 * The lookup addresses sessions-management on its own port, which keeps it out of the `schema`
 * project by construction: that project is defined as needing no stack.
 */
import { expect, type APIRequestContext, type APIResponse } from "@playwright/test";
import { callToken } from "./run.js";
import { noteSession, noteSessionLookup } from "./diagnostics.js";
import { noteReached } from "../registry/reached.js";
import { serviceUrl } from "../env/containers.js";

/** `CamelConstants.Headers.EXTERNAL_SESSION_CIP_ID` (`CamelConstants.java:53`), copied literally. */
export const CORRELATION_HEADER = "external-session-cip-id";

/** A context header the trigger strips and a sender may propagate; `contextBefore` carries it. */
export const REQUEST_ID_HEADER = "X-Request-Id";
/** The `X-Request-Id` a case sends as the caller's. */
export const CALLER_REQUEST_ID = "e2e-request-id";

/**
 * How long a lookup waits for OpenSearch to make the session visible.
 *
 * Measured at 1-2 s from the chain's 200 to a queryable session. 20 s is that with room for a
 * loaded stack, and the lookup hands it to `expect.poll` explicitly rather than inheriting the
 * suite's `expect.timeout`, which is a default for assertions rather than a budget for an indexing
 * lag.
 */
export const SESSION_TIMEOUT = 20_000;

/**
 * Sessions-management on its own port, off the one table every addressed service is read from.
 *
 * Module-private, and the constructor default below is its only caller — the same policy
 * `support/catalog.ts` keeps. Seventeen call sites used to pass `env.url("sessions-management")`
 * explicitly, so the default and this function were never exercised and the base URL was spelled
 * seventeen times for one decision. The `sessions` fixture is the call site now.
 */
function sessionsUrl(): string {
  return serviceUrl("sessions-management");
}

export type ExecutionStatus =
  | "IN_PROGRESS"
  | "COMPLETED_NORMALLY"
  | "COMPLETED_WITH_WARNINGS"
  | "COMPLETED_WITH_ERRORS"
  | "CANCELLED_OR_UNKNOWN";

/**
 * `SessionElementProperty`: one exchange property, as the debugger serialized it.
 *
 * The value is always a string — a property the engine could not serialize arrives as the literal
 * marker `SessionElementProperty.NON_SERIALIZABLE_VALUE` with its real class in `type`.
 */
export interface TracedProperty {
  type: string;
  value: string;
}

/** One step of the trace. Mirrors `SessionElement`, narrowed to what a spec reads. */
export interface TracedElement {
  elementId: string;
  chainElementId: string | null;
  parentElement: string | null;
  previousElement: string | null;
  /** The instance name the fixture chose. Unique within a well-named chain, and what to assert on. */
  elementName: string;
  /** The element type. Repeats across steps, so it identifies a family and never an instance. */
  camelName: string;
  /** The session the step belongs to. Carried by the element payload read as well as by the trace. */
  sessionId?: string;
  executionStatus: ExecutionStatus;
  duration: number;
  /** Local date-times with no zone, as the engine recorded them; comparable within one session. */
  started?: string;
  finished?: string;
  bodyBefore?: string | null;
  bodyAfter?: string | null;
  headersBefore?: Record<string, string> | null;
  headersAfter?: Record<string, string> | null;
  /**
   * The exchange properties the debugger kept, which is not every property the exchange holds:
   * `ExchangeUtils.prepareExchangePropertiesForLogging` drops the platform's own and the variable
   * maps, so what is left is what a chain put there.
   */
  propertiesBefore?: Record<string, TracedProperty> | null;
  propertiesAfter?: Record<string, TracedProperty> | null;
  /** The context headers the trigger took off the request, `X-Request-Id` among them. */
  contextBefore?: Record<string, string> | null;
  exceptionInfo?: { message?: string; stackTrace?: string } | null;
  /** Nested steps. Present and empty on a leaf; the trace is a tree, not a list. */
  children?: TracedElement[] | null;
}

/** Mirrors `Session`. `sessionElements` is `null` — not absent — on a lookup without details. */
export interface RecordedSession {
  id: string;
  /** The chain the exchange ran on. **`null` on an imported session**: the importer clears it. */
  chainId: string | null;
  chainName: string;
  externalSessionCipId: string | null;
  executionStatus: ExecutionStatus;
  duration: number;
  started: string;
  finished: string;
  domain: string;
  /** The kind of engine that ran the chain: `CLASSIC` for the Spring Boot engine, `MICRO` for Quarkus. */
  domainType: "CLASSIC" | "MICRO";
  loggingLevel: string;
  snapshotName: string | null;
  /** What the trigger received as a correlation id, `null` when it received none. */
  correlationId?: string | null;
  /** The session a checkpoint retry resumed, `null` on a session nothing retried. */
  parentSessionId?: string | null;
  /** `true` for a session that arrived through `POST /v1/sessions/import` rather than the engine. */
  importedSession?: boolean;
  sessionElements: TracedElement[] | null;
}

/**
 * `SessionSearchResponse`: one page of light sessions, and the marker for the next request.
 *
 * `offset` is **not** an echo of the offset that was asked for and it is not a total either — it is
 * `offset + rows` (`SessionService.java:220`), the value to send next. So a full page and a last
 * page look alike on the wire and an empty page is the only ending; `sessionsOfRun` in
 * `support/fixtures.ts` loops on exactly that.
 */
export interface SessionPage {
  offset: number;
  sessions: RecordedSession[];
}

/** One clause of a search. The key is **`feature`** here; the catalog's own filters spell it `column`. */
export interface SessionFilter {
  feature: "CHAIN_NAME" | "STATUS" | "START_TIME" | "FINISH_TIME" | "ENGINE";
  condition:
    | "IN"
    | "NOT_IN"
    | "IS_AFTER"
    | "IS_BEFORE"
    | "IS_WITHIN"
    | "CONTAINS"
    | "DOES_NOT_CONTAIN"
    | "STARTS_WITH"
    | "ENDS_WITH";
  value: string;
}

/** `FilterRequestAndSearchDTO`. Both fields are optional, and `{}` is an unfiltered page. */
export interface SessionSearch {
  filterRequestList?: SessionFilter[];
  searchString?: string;
}

/** Where a search reads from and how much of it. No `chainId` is every chain. */
export interface SessionPaging {
  /** Scopes the search to one chain, which is the `/chains/{chainId}` mapping rather than a filter. */
  chainId?: string;
  offset?: number;
  count?: number;
  sortColumn?: string;
}

/** What one attempt at a lookup saw, whether or not it found anything. */
export interface LookupAttempt {
  status: number;
  session: RecordedSession | null;
}

// ---------------------------------------------------------------------------
// Reading a trace — pure, and therefore testable with no stack
// ---------------------------------------------------------------------------

/**
 * Whether the session carries its elements.
 *
 * `!== null` rather than `in`, because a lookup without `includeDetails` answers the key with a
 * null value and `"sessionElements" in session` is true for both.
 */
export function hasTrace(session: RecordedSession): boolean {
  return session.sessionElements !== null && session.sessionElements !== undefined;
}

/**
 * Every step, depth first, parents before their children.
 *
 * The recursion is the point: a branch identity sits below the top level, so a spec that reads
 * `sessionElements` directly asserts over the outline of a chain rather than over what ran.
 */
export function trace(session: RecordedSession): TracedElement[] {
  const flat: TracedElement[] = [];
  const visit = (elements: readonly TracedElement[]): void => {
    for (const element of elements) {
      flat.push(element);
      if (element.children) visit(element.children);
    }
  };
  visit(session.sessionElements ?? []);
  return flat;
}

/** The two steps an HTTP trigger named `HTTP Trigger` records before anything after it runs. */
export const HTTP_TRIGGER_STEPS: readonly string[] = ["HTTP Trigger", "Validate Request"];

/** The instance names that ran, depth first. The readable form of a trace in a failure message. */
export function elementNames(session: RecordedSession): string[] {
  return trace(session).map((each) => each.elementName);
}

/**
 * One step by its instance name, or `undefined`.
 *
 * Deliberately not by `camelName`: that is the type, and `http-echo` alone records it twice.
 */
export function element(session: RecordedSession, elementName: string): TracedElement | undefined {
  return trace(session).find((each) => each.elementName === elementName);
}

/** Every step of one element type, for the assertions that are about a family rather than a name. */
export function elementsOfType(session: RecordedSession, camelName: string): TracedElement[] {
  return trace(session).filter((each) => each.camelName === camelName);
}

/** The steps that did not complete normally, named, for a failure that says which one broke. */
export function failedElements(session: RecordedSession): TracedElement[] {
  return trace(session).filter((each) => each.executionStatus !== "COMPLETED_NORMALLY");
}

// ---------------------------------------------------------------------------
// Calling a chain so the trace can be found again
// ---------------------------------------------------------------------------

export interface ChainCallOptions {
  /** The correlation token. Minted per call when absent — never the run token, which every spec shares. */
  token?: string;
  method?: "POST" | "GET" | "PUT" | "DELETE";
  /** JSON body. An HTTP trigger with no explicit content type reads a body as form data and fails. */
  data?: unknown;
  headers?: Record<string, string>;
}

export interface ChainCall {
  /** The token the call sent, and the only handle on the session it produced. */
  token: string;
  response: APIResponse;
}

/**
 * Calls a deployed chain with a correlation token, and answers with both.
 *
 * The token is per call rather than per run. `GET /v1/sessions/external-id/{id}` answers a *single*
 * session, so a lookup keyed on anything two calls share returns whichever of them the index
 * answers with, and the assertion passes for the wrong reason.
 */
export async function callChain(
  request: APIRequestContext,
  url: string,
  options: ChainCallOptions = {},
): Promise<ChainCall> {
  const token = options.token ?? callToken();
  const response = await request.fetch(url, {
    method: options.method ?? "POST",
    headers: {
      "Content-Type": "application/json",
      [CORRELATION_HEADER]: token,
      ...options.headers,
    },
    ...(options.data === undefined ? {} : { data: options.data as object }),
  });
  return { token, response };
}

// ---------------------------------------------------------------------------
// Finding the trace
// ---------------------------------------------------------------------------

export interface LookupOptions {
  /** How long to wait for the session to become queryable. Defaults to `SESSION_TIMEOUT`. */
  timeout?: number;
  /**
   * How many steps the trace has to carry before the lookup is done. Defaults to **1**.
   *
   * Not a convenience. A session becomes queryable before its elements finish being indexed, so a
   * lookup that returns on the first non-null `sessionElements` hands the caller a **partial**
   * trace — measured under eight workers: a five-step chain came back with two steps, and not the
   * first two, because the elements are written independently and arrive out of order. A spec that
   * knows the shape it expects says how many steps that is, and the poll waits for them.
   *
   * Any value above zero also waits for the session's own `executionStatus` to leave
   * `IN_PROGRESS`, which settles later than the steps do.
   *
   * `0` waits for the session and nothing else, which is the only thing that works at the `ERROR`
   * level: a call that did not fail is recorded as a session whose `sessionElements` stays null.
   */
  elements?: number;
}

export interface SessionsOptions {
  /** sessions-management's base URL; `sessionsUrl()` by default. */
  base?: string;
  /** Changes each session a lookup returns in place, before the caller reads it. */
  rename?: (session: RecordedSession) => void;
}

/** The session lookups, on sessions-management's own port. */
export class Sessions {
  // Plain fields rather than parameter properties, for the same reason `Catalog` uses them:
  // `node --experimental-strip-types` rejects those and the after-the-run steps load this module
  // outside Playwright.
  private readonly api: APIRequestContext;
  private readonly base: string;
  /** Applied to every session a lookup returns: `nameMicroSteps` in a micro case, else nothing. */
  private readonly rename: (session: RecordedSession) => void;

  constructor(api: APIRequestContext, options: SessionsOptions = {}) {
    this.api = api;
    this.base = options.base ?? sessionsUrl();
    this.rename = options.rename ?? (() => {});
  }

  /** A session as sessions-management answered it, after `rename`. */
  private renamed(session: RecordedSession): RecordedSession {
    this.rename(session);
    return session;
  }

  /**
   * One attempt, without throwing.
   *
   * The 404 is the normal answer while the session is being indexed, so it is data here rather than
   * a failure — which is what lets the poll above it tolerate a non-2xx.
   */
  async attemptByExternalId(token: string, includeDetails = true): Promise<LookupAttempt> {
    // Through `send`, so the call is recorded against the operation registry the way every catalog
    // call is: what the run reached is read off the run rather than claimed in the registry.
    const response = await this.send(
      "get",
      `/v1/sessions/external-id/${encodeURIComponent(token)}?includeDetails=${includeDetails}`,
    );
    const body = await response.text();
    return {
      status: response.status(),
      session: response.ok() && body.length ? this.renamed(JSON.parse(body) as RecordedSession) : null,
    };
  }

  /**
   * The session a call produced, waited for and returned with its trace.
   *
   * The failure is the interesting part. A token no chain ever sent and a session still being
   * indexed answer the same 404, so the message names the token and the budget: "waited and never
   * arrived" and "asked for the wrong token" are one sentence apart for whoever reads the report.
   */
  async byExternalId(token: string, options: LookupOptions = {}): Promise<RecordedSession> {
    const timeout = options.timeout ?? SESSION_TIMEOUT;
    noteSessionLookup(token);
    const wanted = options.elements ?? 1;
    let last: LookupAttempt = { status: 0, session: null };
    let seen = 0;
    let found: RecordedSession | undefined;

    // The reading is reported after the wait rather than through the poll's own `message`.
    // `expect.poll` builds its options object once, before the first iteration, so an interpolated
    // `seen` reports the value it was initialized with whatever the polls went on to see — and that
    // reading is the whole diagnosis this failure exists to give.
    try {
      await expect
        .poll(
          async () => {
            last = await this.attemptByExternalId(token);
            const session = last.session;
            if (session === null) return false;
            if (wanted > 0) {
              if (!hasTrace(session)) return false;
              seen = trace(session).length;
              if (seen < wanted) return false;
              // The session's own status settles independently of its elements, and it settles
              // later: measured under load, a nine-step trace came back complete while the session
              // still read `IN_PROGRESS`. Waiting for the steps is therefore not enough for a spec
              // that asserts on the status.
              if (session.executionStatus === "IN_PROGRESS") return false;
            }
            found = session;
            return true;
          },
          { timeout },
        )
        .toBe(true);
    } catch (cause) {
      throw new Error(
        `no settled session carrying ${CORRELATION_HEADER}=${JSON.stringify(token)} arrived ` +
          `within ${timeout} ms with at least ${wanted} step(s). The last read answered ` +
          `HTTP ${last.status} and saw ${seen} step(s) at status ` +
          `${last.session?.executionStatus ?? "none"}. An unused token and a session still being ` +
          `indexed both answer 404, so check that the call actually sent this header before ` +
          `assuming the engine recorded nothing.`,
        { cause },
      );
    }

    if (!found) throw new Error(`unreachable: the poll passed with no session (${last.status})`);
    noteSession({ id: found.id, chainId: found.chainId ?? undefined, token });
    return found;
  }

  // -------------------------------------------------------------------------
  // Transport
  // -------------------------------------------------------------------------
  //
  // Everything below goes out through `send`, for the reason `support/catalog.ts` states at the
  // same seam: `noteReached()` fires inside a transport and nowhere else, so a sessions call made
  // through the raw `request` fixture leaves its operation row unverifiable in both directions. A
  // method here that reached `this.api` directly would record nothing either.
  //
  // The rule that follows: a method here records a call whether or not a spec asserts the operation,
  // and `reconcile()` fails a `not-reached` row that a passing test reached. So a method is added
  // when a spec is about to assert the operation, and not before.
  // `GET /v1/sessions/{sessionId}` is the worked example: it had no method until a spec covered it,
  // at which point `session()` and `rawSession()` below became its transport. `byExternalId` above
  // stays the lookup a spec holding only a call token wants.

  private async send(
    method: string,
    path: string,
    options: Omit<NonNullable<Parameters<APIRequestContext["fetch"]>[1]>, "method"> = {},
  ): Promise<APIResponse> {
    const url = `${this.base}${path}`;
    noteReached("sessions-management", method, url);
    return await this.api.fetch(url, { method: method.toUpperCase(), ...options });
  }

  /**
   * A call that must succeed, returning parsed JSON. An empty body answers `undefined`.
   *
   * `private` like `Engine.call` and unlike `Catalog.call`: no spec reaches this one, and every
   * operation this transport serves has a named method above it. The method is the union rather
   * than `string`, for the reason `Catalog.call` narrows it — a typo'd verb is a compile error
   * instead of a request the service answers 405 to.
   */
  private async call<T>(
    method: "get" | "post" | "put" | "patch" | "delete",
    path: string,
    data?: unknown,
  ): Promise<T> {
    const response = await this.send(method, path, data === undefined ? {} : { data: data as object });
    if (!response.ok()) {
      const body = await response.text().catch(() => "<unreadable>");
      throw new Error(
        `${method.toUpperCase()} ${this.base}${path} answered ${response.status()}: ${body.slice(0, 800)}`,
      );
    }
    const text = await response.text();
    return (text.length ? JSON.parse(text) : undefined) as T;
  }

  /** The raw response, for a spec asserting a status or a failure body. */
  raw(method: string, path: string, data?: unknown): Promise<APIResponse> {
    return this.send(method, path, data === undefined ? {} : { data: data as object });
  }

  // -------------------------------------------------------------------------
  // Searching
  // -------------------------------------------------------------------------

  /**
   * The path a search goes to, which is what decides whether it is chain-scoped.
   *
   * The two mappings are one method behind the controller — `findAllByFilter` delegates to
   * `findByFilter(null, …)` — so they differ in exactly two things: the chain predicate, and the
   * chain-name resolution the bare form does afterwards against the catalog.
   */
  private searchPath(paging: SessionPaging): string {
    const query = new URLSearchParams();
    if (paging.offset !== undefined) query.set("offset", String(paging.offset));
    if (paging.count !== undefined) query.set("count", String(paging.count));
    if (paging.sortColumn !== undefined) query.set("sortColumn", paging.sortColumn);
    const suffix = query.size === 0 ? "" : `?${query.toString()}`;
    return paging.chainId === undefined
      ? `/v1/sessions${suffix}`
      : `/v1/sessions/chains/${encodeURIComponent(paging.chainId)}${suffix}`;
  }

  /** One page of light sessions. `{}` is an unfiltered page rather than an error. */
  search(body: SessionSearch, paging: SessionPaging = {}): Promise<SessionPage> {
    return this.call("post", this.searchPath(paging), body);
  }

  /** The same call, unchecked, for the malformed bodies and the refused sort columns. */
  rawSearch(body: unknown, paging: SessionPaging = {}): Promise<APIResponse> {
    return this.raw("post", this.searchPath(paging), body);
  }

  /** A search with no body at all, which is the zero-byte request the contract answers 400 to. */
  searchWithoutBody(paging: SessionPaging = {}): Promise<APIResponse> {
    return this.send("post", this.searchPath(paging));
  }

  // -------------------------------------------------------------------------
  // One session, one step
  // -------------------------------------------------------------------------

  /**
   * Whether the session exists, as a status code.
   *
   * `HEAD` is the cheap existence check, and one of only two `bwc`-marked operations the module
   * offers. It carries no body whatever it answers, so the status is the whole result.
   */
  async exists(sessionId: string): Promise<number> {
    const response = await this.send("head", `/v1/sessions/${encodeURIComponent(sessionId)}`);
    return response.status();
  }

  /**
   * One session by its own id, trace and all.
   *
   * The read the light listing is the complement of: `POST /v1/sessions` answers
   * `sessionElements: null` whatever the body asked for, and this is the form that carries the
   * tree. It takes the id the platform minted, so a spec that only has a call token goes through
   * `byExternalId` first.
   */
  async session(sessionId: string): Promise<RecordedSession> {
    return this.renamed(await this.call<RecordedSession>("get", `/v1/sessions/${encodeURIComponent(sessionId)}`));
  }

  /** The raw answer of the same read, for the case asserting the miss. */
  rawSession(sessionId: string): Promise<APIResponse> {
    return this.raw("get", `/v1/sessions/${encodeURIComponent(sessionId)}`);
  }

  /**
   * One step with its payload, which the light listing withholds.
   *
   * The listing excludes the eight payload fields (`EXCLUDE_FIELD_IN_SESSIONS`), so this is the
   * only way to read a body without asking for the whole trace.
   */
  elementPayload(sessionId: string, elementId: string): Promise<TracedElement> {
    return this.call("get", this.elementPath(sessionId, elementId));
  }

  /** The same read, unchecked, for the 404 and for the ignored `sessionId`. */
  rawElementPayload(sessionId: string, elementId: string): Promise<APIResponse> {
    return this.raw("get", this.elementPath(sessionId, elementId));
  }

  private elementPath(sessionId: string, elementId: string): string {
    return `/v1/sessions/${encodeURIComponent(sessionId)}/${encodeURIComponent(elementId)}`;
  }

  // -------------------------------------------------------------------------
  // Removing
  // -------------------------------------------------------------------------
  //
  // Four of the five deletes are `refresh=true` and are visible on the very next read.
  // `deleteBySessionId` is the exception — `SessionService.java:151` passes `false` — so a spec
  // that deletes one session by id has to poll for the index to catch up, and one that deletes by
  // chain, by id list, or wholesale does not.

  /** Drops one session. Answers 200 for an id nothing carries: it is a delete by query. */
  deleteSession(sessionId: string): Promise<APIResponse> {
    return this.raw("delete", `/v1/sessions/${encodeURIComponent(sessionId)}`);
  }

  /** Drops every session of one chain. Safe only against a chain the caller created. */
  deleteSessionsOfChain(chainId: string): Promise<APIResponse> {
    return this.raw("delete", `/v1/sessions/chains/${encodeURIComponent(chainId)}`);
  }

  /** The same, for several chains at once. `chainIds` is required: omitting it is a 400. */
  deleteSessionsOfChains(chainIds: readonly string[]): Promise<APIResponse> {
    const query = chainIds.map((id) => encodeURIComponent(id)).join(",");
    return this.raw("delete", `/v1/sessions/chains?chainIds=${query}`);
  }

  /** Drops the sessions the list names, and nothing else. The parallel-safe bulk delete. */
  bulkDelete(sessionIds: readonly string[]): Promise<APIResponse> {
    return this.raw("post", "/v1/sessions/bulk-delete", sessionIds);
  }

  /**
   * Drops **every session in OpenSearch**, for every chain and every run.
   *
   * The one operation of this module that no parallel spec may call. It belongs to
   * `specs/global/`, which runs one worker after `api`, `runtime` and `env`; anywhere else it
   * destroys the traces another worker is mid-assertion over. Named at length for that reason.
   */
  deleteEverySession(): Promise<APIResponse> {
    return this.raw("delete", "/v1/sessions");
  }

  // -------------------------------------------------------------------------
  // Export and import
  // -------------------------------------------------------------------------

  /**
   * The export document for the session ids, as it comes off the wire.
   *
   * One mapping serves `GET` and `POST` — `@RequestMapping(method = {GET, POST})` — and **both
   * carry the id list in the body**, so the `GET` form is a request with a body rather than a query
   * string. The method is the caller's argument because the two are separate registry rows.
   */
  exportSessions(sessionIds: readonly string[], method: "get" | "post" = "post"): Promise<APIResponse> {
    return this.raw(method, "/v1/sessions/export", sessionIds);
  }

  /** Uploads one export document. The multipart field is `files`, and the endpoint takes several. */
  importSessions(fileName: string, document: Buffer): Promise<APIResponse> {
    return this.send("post", "/v1/sessions/import", {
      multipart: { files: { name: fileName, mimeType: "application/json", buffer: document } },
    });
  }

  // -------------------------------------------------------------------------
  // Sessions no correlation header finds
  // -------------------------------------------------------------------------

  /**
   * The one settled session of `chainId` that `filter` keeps, with its trace.
   *
   * For a session no correlation header finds: a call the testing service would mock, a checkpoint
   * retry, or an MCP tool call. The filter is what keeps an earlier session of the chain out.
   */
  async onlyOf(
    chainId: string,
    elements: number,
    filter: (session: RecordedSession) => boolean,
  ): Promise<RecordedSession> {
    let found: RecordedSession | undefined;
    await expect(async () => {
      const kept = (await this.search({}, { chainId })).sessions.filter(filter);
      expect(kept, `sessions of ${chainId}`).toHaveLength(1);
      found = await this.session(kept[0].id);
      expect(hasTrace(found) ? trace(found).length : 0).toBeGreaterThanOrEqual(elements);
      expect(found.executionStatus).not.toBe("IN_PROGRESS");
    }).toPass({ timeout: SESSION_TIMEOUT });
    return found as RecordedSession;
  }

  /** The ids of the chain's latest sessions, read before a call so its session can be told apart. */
  async idsOf(chainId: string): Promise<Set<string>> {
    return new Set((await this.search({}, { chainId })).sessions.map((each) => each.id));
  }
}
