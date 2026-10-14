/**
 * What a transport records about the API call it just made.
 *
 * `covers()` is the element-side twin, and this is the operation side of the same idea: the status
 * in `operations.ts` is a human claim, and the run is what turns it into a checked fact. A spec
 * declares nothing here. The four transports record — `support/catalog.ts`, `support/sessions.ts`,
 * `support/engine.ts` and `support/testing-service.ts` — and so does the `request` fixture itself,
 * through `recordingRequest` at the foot of this file, because a spec that reaches around a client
 * still reaches the endpoint. A declaration a spec has to remember to write is a declaration that
 * goes stale.
 *
 * It lives apart from `operations.ts` because it imports `@playwright/test`, and the post-run
 * reconciliation loads the registry outside Playwright. Same split, same reason, as `covers.ts`.
 */
import { test, type APIRequestContext } from "@playwright/test";
import { REACHED_ANNOTATION } from "./elements.js";
import { matchOperationPath, operationServices, type ServiceName } from "./operations.js";

/** The path of a request, whether the caller has an absolute URL or a bare path. */
function pathOf(url: string): string {
  return url.startsWith("http://") || url.startsWith("https://") ? new URL(url).pathname : url;
}

/**
 * Record one call against the operation registry, once per row and per test.
 *
 * The reader folds these into a `Set`, so a second annotation for a row a test has already recorded
 * changes no answer and costs a line in `report.json` and a row in the HTML report. One run wrote
 * 1289 of them, 87 on a single test, for 123 distinct operations.
 *
 * Silent when the path matches no row, and silent outside a test: `test.info()` throws in
 * `globalSetup` and `globalTeardown`, and the sweep there calls the same transport. Neither case is
 * a claim about coverage, and turning either into a failure would fail a run over its own
 * bookkeeping.
 */
export function noteReached(service: ServiceName, method: string, url: string): void {
  try {
    const key = matchOperationPath(service, method, pathOf(url));
    if (key === undefined) return;
    const { annotations } = test.info();
    const recorded = annotations.some(
      (annotation) => annotation.type === REACHED_ANNOTATION && annotation.description === key,
    );
    if (recorded) return;
    annotations.push({ type: REACHED_ANNOTATION, description: key });
  } catch {
    // Outside a test, or a URL the transport built by hand. Nothing to record either way.
  }
}

// ---------------------------------------------------------------------------
// The seam a spec can reach around a transport with
// ---------------------------------------------------------------------------

/**
 * The service a URL addresses directly, or `undefined` for anything else.
 *
 * Matched on origin against the same table `operationServices()` builds the registry's readers
 * from, so a port that moves moves here too. The proxy's origin is deliberately absent: an `/api/`
 * path is nginx's spelling of a call rather than the service's, and it matches no registry row.
 */
function serviceAt(url: string): ServiceName | undefined {
  let origin: string;
  try {
    origin = new URL(url).origin;
  } catch {
    return undefined;
  }
  return operationServices().find((doc) => new URL(doc.baseUrl).origin === origin)?.service;
}

/** The `APIRequestContext` methods that issue a request. Each names its verb, except `fetch`. */
const REQUEST_METHODS = new Set(["delete", "fetch", "get", "head", "patch", "post", "put"]);

/**
 * The `request` fixture, wrapped so a call it makes on a service's own port records its row.
 *
 * Three reviews in a row found the same defect: a row still marked `not-reached` while a passing
 * spec called the endpoint on every run through the raw `request` fixture, where `noteReached()`
 * never fires. Each was closed one row at a time by moving its caller into a
 * transport, and the next review found another. `specs/api/api-prefixes.spec.ts` is the case a
 * transport cannot absorb — what it asserts is that the proxy and the service's own port answer
 * alike, so half of every comparison is a raw call by construction — and it addresses **every** row
 * of `env/api-routes.ts`, so the shape reappears whenever that table grows.
 *
 * So the recording moves to the seam instead of to the callers. Anything the fixture sends to a
 * service's own port is matched against the registry exactly as a transport call is, and a row the
 * run reaches this way fails `reconcile()` while it still claims `not-reached`. A spec need do
 * nothing, and a row cannot be understated by choosing which client to call it with.
 *
 * What stays outside: a call through the proxy (no row, by design), `globalSetup` and
 * `globalTeardown` (`test.info()` throws, and `noteReached()` is silent there rather than fatal),
 * and the two attachment reads in `support/diagnostics.ts`, which use Node's own `fetch`. None of
 * the three can carry a row's status, which is the property this seam protects.
 */
export function recordingRequest(api: APIRequestContext): APIRequestContext {
  return new Proxy(api, {
    get(target, property) {
      const value: unknown = Reflect.get(target, property);
      if (typeof value !== "function") return value;
      const call = value as (...args: unknown[]) => unknown;
      if (typeof property !== "string" || !REQUEST_METHODS.has(property)) {
        // Bound to the context rather than to the proxy: these reach the channel's internals.
        return call.bind(target);
      }
      return (...args: unknown[]) => {
        const [url, options] = args as [unknown, { method?: string } | undefined];
        // `fetch` also takes a `Request`, which carries no URL this reader can read.
        if (typeof url === "string") {
          const service = serviceAt(url);
          if (service !== undefined) {
            noteReached(service, property === "fetch" ? (options?.method ?? "GET") : property, url);
          }
        }
        return call.apply(target, args);
      };
    },
  });
}
