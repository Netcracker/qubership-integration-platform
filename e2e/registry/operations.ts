/**
 * The API operation registry: one row per HTTP operation the services serve.
 *
 * Its job is to show where nobody has been at all. The element registry answers "does the suite
 * exercise this element"; this one answers "has any spec ever called this endpoint", which is a
 * different and much cheaper question — and the two must not be added together.
 *
 * Three states, not two, because a one-spec-per-endpoint sweep earns the first and not the second:
 *
 * - `not-reached` — no spec calls it. A `reason` is required, so a gap is a decision on the record.
 * - `reached` — a spec called it and it answered. Cheap, and worth exactly what it costs.
 * - `covered` — a named assertion about the operation's contract passed: its response shape, its
 *   side effect, or its failure path.
 *
 * Reporting `reached` and `covered` as one number is how a suite claims 100% while proving only
 * that the platform still returns 200. `summarizeOperations` keeps them apart and every report
 * that quotes one has to quote the other.
 *
 * Two readings guard the registry, and neither trusts the other. `specs/schema/api-coverage.spec.ts`
 * diffs the registry against `operations.cache.json` without a stack; `specs/api/api-docs-live.spec.ts`
 * diffs that cache against what the services serve right now. Without the second, a controller added
 * to the catalog fails nothing: the guard would only prove the registry tracks a file the registry's
 * own author last wrote.
 *
 * `reached` is derived rather than trusted, in both directions. `noteReached()` in `reached.ts`
 * records every call a transport makes as an annotation on the running test — the transports are
 * `support/catalog.ts`, `support/sessions.ts`, `support/engine.ts` and `support/testing-service.ts`,
 * and nothing else records — `matchOperationPath`
 * below is what turns a concrete URL back into a row, and `reconcile()` fails a `covered` row that
 * no passing test reached **and** a `not-reached` row that one did. Without the first a status is a
 * sentence somebody typed and the spec behind it can be deleted with nothing to say so; without the
 * second the registry understates the suite, and files work that is finished as a gap.
 *
 * The module imports `node:fs` and the dependency-free service table at runtime, so it loads
 * outside Playwright. `env/containers.ts` imports only `env/target.ts`, which imports nothing, and
 * `support/report.ts` already reaches it under `node --experimental-strip-types`. That is also why
 * the specifier below names the `.ts` file: a `.js` specifier onto a `.ts` file resolves to nothing
 * there.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { serviceUrl } from "../env/containers.ts";
import type { ServiceRole } from "../env/index.ts";
import type { Target } from "../env/target.ts";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** The reduced operation list, cached so the gap spec needs no stack. */
export const CACHE_FILE = path.join(HERE, "operations.cache.json");

// ---------------------------------------------------------------------------
// The services and their documents
// ---------------------------------------------------------------------------

export type ServiceName = "catalog" | "engine" | "sessions-management" | "testing-service";

export interface ServiceDoc {
  service: ServiceName;
  /** Overridable so a refresh can read a stack that is not on localhost. */
  baseUrl: string;
  /**
   * Where the service publishes its document, because the four do not agree: the three Java
   * services serve OpenAPI 3 at `/v3/api-docs`, and the testing service is a Go module serving
   * **Swagger 2.0** at `/api/v1/swagger/doc.json`.
   */
  docPath: string;
}

/**
 * Read at call time rather than frozen at import, so a spec can set the environment first.
 *
 * The base URLs come off `env/containers.ts` rather than being spelled here. The registry calls the
 * catalog `catalog` where Compose calls it `runtime-catalog`, which is the whole of the difference
 * between the two names and the reason for the pairing below.
 *
 * The testing service is here for the same reason the other three are: the registry is not
 * catalog-scoped, and a service the refresh does not read is a service no gap detector can ever
 * flag an endpoint on. It answers on a different path, which is what `docPath` carries, and in a
 * different document format, which costs the reader below nothing: see `operationsFromOpenApi`.
 */
export function operationServices(): ServiceDoc[] {
  const documented: Array<[ServiceName, ServiceRole, string]> = [
    ["catalog", "runtime-catalog", "/v3/api-docs"],
    ["engine", "engine", "/v3/api-docs"],
    ["sessions-management", "sessions-management", "/v3/api-docs"],
    ["testing-service", "testing-service", "/api/v1/swagger/doc.json"],
  ];
  return documented.map(([service, role, docPath]) => ({
    service,
    baseUrl: serviceUrl(role),
    docPath,
  }));
}

/** The verbs a path item may carry. Anything else under a path — `parameters`, `servers` — is not
 * an operation, and counting it inflates every number the registry reports. */
const HTTP_METHODS = ["get", "put", "post", "delete", "options", "head", "patch", "trace"];

export interface OpenApiDocument {
  paths?: Record<string, Record<string, unknown>>;
}

/**
 * An API document reduced to a sorted list of `METHOD /path` strings, spelled as a request sends
 * them.
 *
 * One reducer for both document shapes, because a Swagger 2.0 path item holds the same verbs under
 * the same keys as an OpenAPI 3 one. The field that could have separated them is `basePath`, which
 * Swagger 2.0 prefixes onto every path: measured, the testing service serves an empty one and
 * writes `/api/v1` into each path itself, so there is nothing to prefix and no branch here to do it.
 */
export function operationsFromOpenApi(document: OpenApiDocument): string[] {
  const operations: string[] = [];
  for (const [route, item] of Object.entries(document.paths ?? {})) {
    for (const method of Object.keys(item ?? {})) {
      if (!HTTP_METHODS.includes(method.toLowerCase())) continue;
      operations.push(`${method.toUpperCase()} ${route}`);
    }
  }
  return operations.sort();
}

export interface CachedOperations {
  services: Record<string, string[]>;
}

export function loadCachedOperations(file: string = CACHE_FILE): CachedOperations {
  return JSON.parse(fs.readFileSync(file, "utf-8")) as CachedOperations;
}

/** Every cached operation as a registry key, so the two sides compare as flat sets. */
export function cachedOperationKeys(cache: CachedOperations = loadCachedOperations()): string[] {
  const keys: string[] = [];
  for (const [service, operations] of Object.entries(cache.services)) {
    for (const operation of operations) keys.push(`${service} ${operation}`);
  }
  return keys.sort();
}

// ---------------------------------------------------------------------------
// The registry
// ---------------------------------------------------------------------------

/** `reached` is "it answered"; `covered` is "a named assertion about its contract passed". */
export type OperationStatus = "not-reached" | "reached" | "covered";

export interface OperationEntry {
  service: ServiceName;
  method: string;
  path: string;
  /** The document's own tag for the operation, which is the controller. Readability only. */
  controller: string;
  status: OperationStatus;
  /** Required whenever `status` is `not-reached`. */
  reason?: string;
  /**
   * The one target whose run can reach the operation. Absent means both, and reconciliation reads
   * the row only against a run on its target.
   */
  target?: Target;
}

/** The row key, and `entryKey` below is its one caller. */
function operationKey(service: string, method: string, route: string): string {
  return `${service} ${method} ${route}`;
}

export function entryKey(entry: OperationEntry): string {
  return operationKey(entry.service, entry.method, entry.path);
}

export interface OperationSummary {
  total: number;
  notReached: number;
  reached: number;
  covered: number;
}

/**
 * The two numbers, side by side and never summed. `reached` counts the operations a spec has
 * called at all — `covered` rows included, since a contract assertion implies the call.
 */
export function summarizeOperations(
  entries: readonly OperationEntry[] = operationRegistry,
): OperationSummary {
  const covered = entries.filter((entry) => entry.status === "covered").length;
  const reachedOnly = entries.filter((entry) => entry.status === "reached").length;
  return {
    total: entries.length,
    notReached: entries.filter((entry) => entry.status === "not-reached").length,
    reached: reachedOnly + covered,
    covered,
  };
}

/** The problems in a registry's own shape, as human-readable lines. Empty means valid. */
export function validateOperationRegistry(entries: readonly OperationEntry[]): string[] {
  const problems: string[] = [];
  const seen = new Set<string>();

  for (const entry of entries) {
    const key = entryKey(entry);
    if (seen.has(key)) problems.push(`duplicate row: ${key}`);
    seen.add(key);

    if (!HTTP_METHODS.includes(entry.method.toLowerCase())) {
      problems.push(`${key}: ${entry.method} is not an HTTP method`);
    }
    if (entry.method !== entry.method.toUpperCase()) {
      problems.push(`${key}: the method is not written in upper case`);
    }
    if (!entry.path.startsWith("/")) problems.push(`${key}: the path does not start with "/"`);
    if (!entry.controller) problems.push(`${key}: no controller`);
    if (entry.status === "not-reached" && !entry.reason?.trim()) {
      problems.push(`${key}: status is not-reached and no reason is given`);
    }
  }
  return problems;
}

export interface OperationDiff {
  /** In `expected` and not in `actual`. */
  missing: string[];
  /** In `actual` and not in `expected`. */
  unexpected: string[];
}

export function diffOperations(
  expected: readonly string[],
  actual: readonly string[],
): OperationDiff {
  const left = new Set(expected);
  const right = new Set(actual);
  return {
    missing: [...left].filter((key) => !right.has(key)).sort(),
    unexpected: [...right].filter((key) => !left.has(key)).sort(),
  };
}

// ---------------------------------------------------------------------------
// What the run reached
// ---------------------------------------------------------------------------

/**
 * A concrete request path matched back to the registry row that serves it, or `undefined`.
 *
 * `undefined` is not a failure. The registry covers what the four service documents declare, so a
 * call to `/actuator/health` or to a service outside them is no claim about any row, and recording
 * it would only invent a problem for the reconciliation to report.
 */
export function matchOperationPath(
  service: string,
  method: string,
  requestPath: string,
  entries: readonly OperationEntry[] = operationRegistry,
): string | undefined {
  const bare = requestPath.split("?")[0].split("#")[0];
  const segments = (bare.startsWith("/") ? bare : `/${bare}`).split("/");
  const verb = method.toUpperCase();

  let best: OperationEntry | undefined;
  let bestLiterals = -1;

  for (const entry of entries) {
    if (entry.service !== service || entry.method.toUpperCase() !== verb) continue;
    const template = entry.path.split("/");
    if (template.length !== segments.length) continue;

    let literals = 0;
    let matches = true;
    for (let index = 0; index < template.length; index++) {
      const part = template[index];
      if (part.startsWith("{") && part.endsWith("}")) continue;
      if (part !== segments[index]) {
        matches = false;
        break;
      }
      literals++;
    }

    // `/v1/sessions/export` and `/v1/sessions/{sessionId}` both match a request for
    // `/v1/sessions/export`; the more literal template is the one that served it.
    if (matches && literals > bestLiterals) {
      best = entry;
      bestLiterals = literals;
    }
  }

  return best === undefined ? undefined : entryKey(best);
}

/**
 * The registry as `reconcile()` reads it: one key and one claim per row.
 *
 * The reduction happens here rather than there because `elements.ts` loads outside Playwright,
 * where it cannot import this module — the same constraint that has `support/report.ts` handed its
 * component tags. `npm run reconcile` is the one caller.
 */
export function operationClaims(
  entries: readonly OperationEntry[] = operationRegistry,
): { key: string; status: OperationStatus; target?: Target }[] {
  return entries.map((entry) => ({ key: entryKey(entry), status: entry.status, target: entry.target }));
}

// ---------------------------------------------------------------------------
// The seed
// ---------------------------------------------------------------------------

const R_NO_CASE = "no case was written for it";
// Measured 500 on both against the running stack: no MaaS agent is reachable (maas.agent.url
// resolves empty; MAAS_AGENT_URL is unset in every compose env file). The declarative pair needs no
// agent at all and is covered instead.
const R_MAAS_AGENT_ABSENT = "no MaaS agent reachable locally, measured 500";
// A row left `not-reached` while a passing spec already calls it understates the suite, and three
// separate reviews caught the registry doing exactly that. Every time, the call was made outside a
// transport, where `noteReached()` did not fire: the three endpoint-mock rows
// `specs/tooling/mock-smoke.spec.ts` drives; `GET /api/v1/test-cases/{id}`, `POST /api/v1/test-cases`
// and `GET /api/v1/mode`, which `specs/api/error-contract.spec.ts` and `specs/api/health.spec.ts`
// assert; and `GET /api/v1/endpoint-mocks/call`, which the `blocked` loop of
// `specs/api/api-prefixes.spec.ts` proves the testing service serves. `GET /v1/sessions/{sessionId}`
// was an earlier one. Each instance was closed by hand, and the next review found the next.
//
// **The shape is closed mechanically now rather than row by row.** `recordingRequest` in
// `reached.ts` wraps the `request` fixture, so a call it sends to a service's own port is matched
// against this registry exactly as a transport call is, and `reconcile()` fails a `not-reached` row
// the run reached whichever client sent the request. `api-prefixes` is why it had to be the seam
// and not the callers: what that spec asserts is that the proxy and the service's own port answer
// alike, so half of every comparison is a raw call by construction, and it addresses **every** row
// of `env/api-routes.ts` — the shape would come back the next time that table grew.
//
// Three kinds of call still record nothing, and none of them can carry a row's status. A request
// through **nginx** matches no row by design, which is what `specs/api/api-prefixes.spec.ts` and
// `specs/env/restart-resilience.spec.ts` send for `/api/v1/qip/engine/live-exchanges`.
// `globalSetup` and `globalTeardown` are outside a test, where `test.info()` throws and
// `noteReached()` is silent rather than fatal. And `support/diagnostics.ts` reads a session for an
// attachment with Node's own `fetch`, over a row `specs/api/sessions-api.spec.ts` covers anyway.

/**
 * Seeded from the four documents as they stand, every row `not-reached` with its reason, and a row
 * moves off `not-reached` together with the spec that earns it. `CustomResource` and `Discovery`
 * need Kubernetes and are covered on that target, which their rows name with `target: "k8s"`.
 * `MaasActions`'s two agent-backed rows stay `not-reached`, because this stack can provision no
 * agent; its declarative pair needs no agent and is covered.
 *
 * A spec that calls an endpoint without asserting its contract moves the row to `reached` and no
 * further. `reconcile()` fails a `not-reached` row a passing test reached, so a status cannot be
 * understated.
 *
 * `GET /v1/catalog/runtime-deployments` is covered by `specs/global/runtime-deployments.spec.ts`,
 * which pins that the response is a dict keyed by chain id rather than a list, and what each row
 * carries, because every deployment assertion in the suite polls it.
 *
 * The five `/v1/secured-variables` rows are `covered` by a failure-path assertion rather than by a
 * write. Every one of them answers **410 `Default secret functionality is disabled`** on this stack,
 * because they address the default secret and `qip.variables.default-secret.enabled` is false, so
 * the 410 is the operation's contract as configured and pinning it is the only honest reading. The
 * behaviour those endpoints used to carry is exercised through `/v2/secured-variables`, and those
 * six rows are `covered`: `specs/api/variables.spec.ts` creates, reads, updates and deletes a
 * variable in a named secret and asserts that the value never comes back on either read path, which
 * is the contract of every one of them.
 *
 * `SecretControllerV2` has two operations and `specs/api/secret-name.spec.ts` asserts both. The
 * create is pinned on both paths: 200 for a name the store already holds, 400 quoting the name
 * pattern for one outside it. The template is pinned on the download it serves, the
 * `Content-Disposition` filename and the `stringData` fragment in the body.
 *
 * `ActionsLogController` is `@Deprecated(since = "2026.3")` and `ActionsLogControllerV2` serves the
 * same search over `offset`/`limit`; `specs/api/audit.spec.ts` asserts both.
 *
 * `GET /v1/export/system` and `POST /v1/import/system` are covered by
 * `specs/api/concurrent-service-import.spec.ts` rather than by `specs/api/import-export.spec.ts`.
 * That case asserts the exact archive entries and round-trips the original bytes through a
 * delete, which is the contract. `POST /v1/import/systemPreview` is covered by
 * `specs/api/services.spec.ts`, beside the context and MCP previews. `POST /v1/export/system` is
 * the one of that family nothing asserts yet, and it stays `not-reached`.
 *
 * `specs/api/specifications.spec.ts` imports a specification and asserts what the import produced,
 * and that a delete is refused until a deprecate has happened: `GET /v1/models`,
 * `GET /v1/models/{modelId}`, `DELETE /v1/models/{modelId}`, `POST /v1/models/deprecated` and
 * `GET /v1/operations`. `specs/api/system-models.spec.ts` covers the rest of both families: the
 * listing's two filters and its empty bare form, `/v1/models/latest`, `/v1/models/{modelId}/source`,
 * `PATCH /v1/models/{modelId}`, and every `/v1/operations/{operationId}` shape. The compiled library
 * is the one row of the neighbourhood that belongs elsewhere, and `specs/api/libraries.spec.ts` has
 * it.
 *
 * `chain-roles-controller` earns **six** rows from three operations, because
 * `@RequestMapping(value = {"/v1/catalog/chains/roles", "/v1/catalog/chains/access-control"})` maps
 * it at two paths and the registry is keyed by `METHOD /path`. `specs/api/chain-roles.spec.ts`
 * drives both on every case rather than assuming the pairing, which is what makes the other three
 * rows reached at all.
 *
 * **`sessions-management` is covered whole.** `specs/api/sessions-api.spec.ts` covers thirteen of
 * the fourteen rows, `GET /v1/sessions/external-id/{externalSessionId}` included, and
 * `specs/global/sessions-destructive.spec.ts` the bare `DELETE /v1/sessions`. The broker specs use
 * `POST /v1/sessions/chains/{chainId}` for the sftp and cron triggers, which carry no inbound message
 * and so cannot be correlated by external id.
 *
 * **The testing service is the fourth service, and its 40 rows are the denominator's last third.**
 * The covered count is read against what the platform serves, and the platform is four services:
 * 243 catalog, 14 sessions-management, 6 engine and 40 testing-service, 303 in all. A registry holding only the first three reads as complete at 263
 * while a whole product is untracked, so the rows land here together with the reducer that reads
 * their Swagger 2.0 document.
 *
 * All 40 are `covered`. Four specs assert what the service does:
 * `specs/api/testing-service-mocks.spec.ts` covers the endpoint mocks, including
 * `/endpoint-mocks/call` on all eight methods it serves (`routes.conf:130` blocks that path at the
 * proxy, so it is called on the service's own port); `specs/api/testing-service-portability.spec.ts`
 * covers the four export and import rows; `specs/runtime/testing-service-cases.spec.ts` covers the
 * test cases; and `specs/runtime/testing-service-runs.spec.ts` covers both run families,
 * `/tests-runs` and `/test-case-runs`. `specs/api/error-contract.spec.ts` pins the failure contract of
 * `GET /api/v1/test-cases/{id}` and `POST /api/v1/test-cases`, and `specs/api/health.spec.ts` pins
 * the document `GET /api/v1/mode` answers.
 *
 * Three more specs use the service as a tool and assert nothing about its answers:
 * `specs/tooling/mock-smoke.spec.ts`, `specs/ui/testing-section.spec.ts`, and
 * `specs/api/api-prefixes.spec.ts`, which proves the proxy's block by calling
 * `GET /api/v1/endpoint-mocks/call` on the service's own port through `recordingRequest`.
 */
export const operationRegistry: OperationEntry[] = [
  { service: "catalog", method: "DELETE", path: "/v1/catalog/chains/{chainId}/deployments", controller: "deployment-controller", status: "reached" },
  { service: "catalog", method: "DELETE", path: "/v1/catalog/chains/{chainId}/deployments/{deploymentId}", controller: "deployment-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/catalog/chains/{chainId}/snapshots", controller: "snapshot-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/catalog/chains/{chainId}/snapshots/{snapshotId}", controller: "snapshot-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/catalog/context-system/{contextId}", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/catalog/import-instructions", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/catalog/live-exchanges/{podIp}/{deploymentId}/{exchangeId}", controller: "live-exchanges-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/catalog/mcp-system/{id}", controller: "mcp-system-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}/dependencies", controller: "dependency-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}/dependencies/{dependencyId}", controller: "dependency-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}/elements", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}/elements/groups/{groupId}", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}/elements/{elementId}", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}/masking/field/{fieldId}", controller: "masked-fields-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/chains/{chainId}/properties/logging", controller: "logging-properties-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/common-variables", controller: "common-variables-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/cr/{name}", controller: "custom-resource-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "DELETE", path: "/v1/cr/{name}/{snapshotId}", controller: "custom-resource-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "DELETE", path: "/v1/detailed-design/templates", controller: "detailed-design-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/folders/{folderId}", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/models/{modelId}", controller: "system-model-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/secured-variables", controller: "secured-variable-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/specificationGroups/{specificationGroupId}", controller: "specification-group-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/systems/{systemId}", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v1/systems/{systemId}/environments/{environmentId}", controller: "environment-controller", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v2/folders/{id}", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v2/secured-variables", controller: "secured-variable-controller-v-2", status: "covered" },
  { service: "catalog", method: "DELETE", path: "/v2/secured-variables/{secretName}", controller: "secured-variable-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/actions-log/export", controller: "actions-log-export-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/chains/{chainId}/deployments", controller: "deployment-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/chains/{chainId}/deployments/{deploymentId}", controller: "deployment-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/chains/{chainId}/snapshots", controller: "snapshot-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/chains/{chainId}/snapshots/{snapshotId}", controller: "snapshot-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/context-system", controller: "context-system-controller", status: "reached" },
  { service: "catalog", method: "GET", path: "/v1/catalog/context-system/export", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/context-system/{contextId}", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/diagnostic/validations/{validationId}", controller: "diagnostic-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/domains", controller: "engine-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/domains/hosts", controller: "engine-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/domains/{domainName}/deployments/count", controller: "engine-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/domains/{domainName}/engines", controller: "engine-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/domains/{domainName}/engines/{engineHost}/deployments", controller: "engine-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/events", controller: "event-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/export", controller: "export-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/export/api-spec", controller: "export-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/export/chain/{chainId}", controller: "export-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/export/chains", controller: "export-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/import-instructions", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/import-instructions/export", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/live-exchanges", controller: "live-exchanges-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/mcp-system", controller: "mcp-system-controller", status: "reached" },
  { service: "catalog", method: "GET", path: "/v1/catalog/mcp-system/{id}", controller: "mcp-system-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/runtime-deployments", controller: "runtime-deployment-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/validation/findRouteDeployments", controller: "element-validation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/catalog/validation/routes", controller: "element-validation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains", controller: "chain-controller", status: "reached" },
  { service: "catalog", method: "GET", path: "/v1/chains/count", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/find-by-element/{elementId}", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/names", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/systems/{systemId}", controller: "chain-controller", status: "not-reached", reason: R_NO_CASE },
  { service: "catalog", method: "GET", path: "/v1/chains/used-systems", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/dependencies", controller: "dependency-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/dependencies/{dependencyId}", controller: "dependency-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/elements", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/elements/code", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/elements/properties/used", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/elements/type/{type}", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/elements/{elementId}", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/masking", controller: "masked-fields-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/properties/logging", controller: "logging-properties-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/chains/{systemId}/model/{modelId}", controller: "chain-controller", status: "not-reached", reason: R_NO_CASE },
  { service: "catalog", method: "GET", path: "/v1/chains/{systemId}/specificationGroup", controller: "chain-controller", status: "not-reached", reason: R_NO_CASE },
  { service: "catalog", method: "GET", path: "/v1/chains/{systemId}/specificationGroup/{groupId}", controller: "chain-controller", status: "not-reached", reason: R_NO_CASE },
  { service: "catalog", method: "GET", path: "/v1/chains/{systemId}/{operationId}", controller: "chain-controller", status: "not-reached", reason: R_NO_CASE },
  { service: "catalog", method: "GET", path: "/v1/common-variables", controller: "common-variables-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/common-variables/export", controller: "common-variables-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/design-generator/chains/{chainId}", controller: "chain-design-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/design-generator/chains/{chainId}/snapshots/{snapshotId}", controller: "chain-design-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/detailed-design/chains/{chainId}", controller: "detailed-design-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/detailed-design/templates", controller: "detailed-design-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/detailed-design/templates/{templateId}", controller: "detailed-design-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/export/specifications", controller: "specification-export-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/export/system", controller: "system-export-import-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/folders", controller: "folder-controller", status: "reached" },
  { service: "catalog", method: "GET", path: "/v1/folders/{folderId}", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/folders/{folderId}/chains", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/folders/{folderId}/elements", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/import/{importId}", controller: "specification-import-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/library", controller: "element-library-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/library/elements/types", controller: "element-library-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/library/{name}", controller: "element-library-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/models", controller: "system-model-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/models/latest", controller: "system-model-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/models/{modelId}", controller: "system-model-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/models/{modelId}/dto/jar", controller: "compiled-library-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/models/{modelId}/source", controller: "system-model-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/operations", controller: "operation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/operations/{operationId}", controller: "operation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/operations/{operationId}/info", controller: "operation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/operations/{operationId}/schemas", controller: "operation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/operations/{operationId}/schemas/request", controller: "operation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/operations/{operationId}/schemas/response", controller: "operation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/operations/{operationId}/specification", controller: "operation-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/secured-variables", controller: "secured-variable-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/specificationGroups", controller: "specification-group-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/systems", controller: "system-controller", status: "reached" },
  { service: "catalog", method: "GET", path: "/v1/systems/discovery", controller: "discovery-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "GET", path: "/v1/systems/discovery/progress", controller: "discovery-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "GET", path: "/v1/systems/discovery/result", controller: "discovery-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "GET", path: "/v1/systems/usage", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/systems/{systemId}", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/systems/{systemId}/environments", controller: "environment-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v1/systems/{systemId}/environments/{environmentId}", controller: "environment-controller", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/catalog/snapshots/{snapshotId}/full", controller: "snapshot-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/folders/path", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/folders/{id}", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/folders/{id}/path", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/import/preview/{importId}/status", controller: "import-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/import/status/{importId}", controller: "import-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/import/{importId}", controller: "import-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/secret/template/{secretName}", controller: "secret-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/secured-variables", controller: "secured-variable-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v2/secured-variables/{secretName}", controller: "secured-variable-controller-v-2", status: "covered" },
  { service: "catalog", method: "GET", path: "/v3/import", controller: "import-controller-v-3", status: "covered" },
  { service: "catalog", method: "GET", path: "/v3/import/{importId}", controller: "import-controller-v-3", status: "covered" },
  { service: "catalog", method: "HEAD", path: "/v1/chains/{chainId}", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/catalog/context-system/{contextId}", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/catalog/diagnostic/validations", controller: "diagnostic-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/catalog/import-instructions", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/chains/{chainId}/elements/{elementId}", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/chains/{chainId}/migrate", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/common-variables/{name}", controller: "common-variables-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/models/{modelId}", controller: "system-model-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/secured-variables/{securedVariableName}", controller: "secured-variable-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/specificationGroups/{specificationGroupId}", controller: "specification-group-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v1/systems/{systemId}", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "PATCH", path: "/v2/secured-variables", controller: "secured-variable-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/actions-log", controller: "actions-log-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/chains/access-control", controller: "chain-roles-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/chains/deployments/bulk", controller: "bulk-deployment-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/chains/roles", controller: "chain-roles-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/chains/{chainId}/deployments", controller: "deployment-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/chains/{chainId}/deployments/all", controller: "deployment-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/chains/{chainId}/snapshots", controller: "snapshot-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/chains/{chainId}/snapshots/{snapshotId}/revert", controller: "snapshot-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/context-system", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/context-system/export", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/context-system/filter", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/context-system/import", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/context-system/import/preview", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/context-system/search", controller: "context-system-controller", status: "reached" },
  { service: "catalog", method: "POST", path: "/v1/catalog/diagnostic/validations", controller: "diagnostic-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/domains/{domainName}/deployments/update", controller: "engine-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/import", controller: "import-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/import-instructions", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/import-instructions/filter", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/import-instructions/search", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/import-instructions/upload", controller: "import-instructions-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/import/preview", controller: "import-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/live-exchanges", controller: "live-exchanges-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/maintenance/snapshots/prune", controller: "maintenance-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/mcp-system", controller: "mcp-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/mcp-system/export", controller: "mcp-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/mcp-system/filter", controller: "mcp-system-controller", status: "reached" },
  { service: "catalog", method: "POST", path: "/v1/catalog/mcp-system/import", controller: "mcp-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/catalog/mcp-system/import/preview", controller: "mcp-system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/bulk-delete", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/diff", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/copy", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/dependencies", controller: "dependency-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/duplicate", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/elements", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/elements/clone", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/elements/code", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/elements/groups", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/elements/transfer", controller: "element-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/masking", controller: "masked-fields-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/masking/field", controller: "masked-fields-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/move", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/chains/{chainId}/properties/logging", controller: "logging-properties-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/common-variables", controller: "common-variables-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/common-variables/import", controller: "common-variables-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/common-variables/preview", controller: "common-variables-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/cr", controller: "custom-resource-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "POST", path: "/v1/cr/deploy", controller: "custom-resource-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "POST", path: "/v1/cr/deploy-chains", controller: "custom-resource-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "POST", path: "/v1/design-generator/chains/{chainId}", controller: "chain-design-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/design-generator/chains/{chainId}/snapshots/{snapshotId}", controller: "chain-design-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/export/system", controller: "system-export-import-controller", status: "not-reached", reason: R_NO_CASE },
  { service: "catalog", method: "POST", path: "/v1/folders", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/folders/filter", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/folders/search", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/folders/{folderId}/move", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/import", controller: "specification-import-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/import/system", controller: "system-export-import-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/import/systemPreview", controller: "system-export-import-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/maas-actions/kafka", controller: "maas-actions-controller", status: "not-reached", reason: R_MAAS_AGENT_ABSENT },
  { service: "catalog", method: "POST", path: "/v1/maas-actions/kafka/declarative", controller: "maas-actions-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/maas-actions/rabbitmq", controller: "maas-actions-controller", status: "not-reached", reason: R_MAAS_AGENT_ABSENT },
  { service: "catalog", method: "POST", path: "/v1/maas-actions/rabbitmq/declarative", controller: "maas-actions-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/models/deprecated", controller: "system-model-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/secured-variables", controller: "secured-variable-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/secured-variables/import", controller: "secured-variable-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/specificationGroups", controller: "specification-group-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/specificationGroups/import", controller: "specification-group-import-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/systems", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/systems/discovery", controller: "discovery-controller", status: "covered", target: "k8s" },
  { service: "catalog", method: "POST", path: "/v1/systems/filter", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/systems/search", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v1/systems/{systemId}/environments", controller: "environment-controller", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/catalog/actions-log", controller: "actions-log-controller-v2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/catalog/snapshots/bulk-delete", controller: "snapshot-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/common-variables/import", controller: "common-variables-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/folders", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/folders/bulk-delete", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/folders/list", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/folders/move", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/import", controller: "import-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/secret/{secretName}", controller: "secret-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v2/secured-variables", controller: "secured-variable-controller-v-2", status: "covered" },
  { service: "catalog", method: "POST", path: "/v3/import", controller: "import-controller-v-3", status: "covered" },
  { service: "catalog", method: "POST", path: "/v3/import/chains/diff", controller: "import-controller-v-3", status: "covered" },
  { service: "catalog", method: "POST", path: "/v3/import/chains/extract", controller: "import-controller-v-3", status: "covered" },
  { service: "catalog", method: "POST", path: "/v3/import/preview", controller: "import-controller-v-3", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/catalog/chains/access-control", controller: "chain-roles-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/catalog/chains/access-control/redeploy", controller: "chain-roles-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/catalog/chains/roles", controller: "chain-roles-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/catalog/chains/roles/redeploy", controller: "chain-roles-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/catalog/chains/{chainId}/snapshots/{snapshotId}", controller: "snapshot-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/catalog/context-system/{contextId}", controller: "context-system-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/catalog/mcp-system/{id}", controller: "mcp-system-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/chains/{chainId}", controller: "chain-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/chains/{chainId}/elements/properties-modification", controller: "element-modification-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/chains/{chainId}/masking/field/{fieldId}", controller: "masked-fields-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/detailed-design/templates", controller: "detailed-design-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/folders/{folderId}", controller: "folder-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/systems/{systemId}", controller: "system-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v1/systems/{systemId}/environments/{environmentId}", controller: "environment-controller", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v2/folders/{id}", controller: "folder-controller-v-2", status: "covered" },
  { service: "catalog", method: "PUT", path: "/v3/rollout-import/{snapshotId}", controller: "rollout-import", status: "covered" },
  { service: "engine", method: "DELETE", path: "/v1/engine/live-exchanges/{deploymentId}/{exchangeId}", controller: "live-exchanges-controller", status: "covered" },
  { service: "engine", method: "GET", path: "/v1/engine/chains/{chainId}/sessions/failed", controller: "checkpoint-session-controller", status: "covered" },
  { service: "engine", method: "GET", path: "/v1/engine/live-exchanges", controller: "live-exchanges-controller", status: "covered" },
  { service: "engine", method: "GET", path: "/v1/engine/sessions", controller: "session-controller", status: "covered" },
  { service: "engine", method: "POST", path: "/v1/engine/chains/{chainId}/sessions/{sessionId}/checkpoint-elements/{checkpointElementId}/retry", controller: "checkpoint-session-controller", status: "covered" },
  { service: "engine", method: "POST", path: "/v1/engine/chains/{chainId}/sessions/{sessionId}/retry", controller: "checkpoint-session-controller", status: "covered" },
  { service: "sessions-management", method: "DELETE", path: "/v1/sessions", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "DELETE", path: "/v1/sessions/chains", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "DELETE", path: "/v1/sessions/chains/{chainId}", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "DELETE", path: "/v1/sessions/{sessionId}", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "GET", path: "/v1/sessions/export", controller: "export-controller", status: "covered" },
  { service: "sessions-management", method: "GET", path: "/v1/sessions/external-id/{externalSessionId}", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "GET", path: "/v1/sessions/{sessionId}", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "GET", path: "/v1/sessions/{sessionId}/{elementId}", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "HEAD", path: "/v1/sessions/{sessionId}", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "POST", path: "/v1/sessions", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "POST", path: "/v1/sessions/bulk-delete", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "POST", path: "/v1/sessions/chains/{chainId}", controller: "session-controller", status: "covered" },
  { service: "sessions-management", method: "POST", path: "/v1/sessions/export", controller: "export-controller", status: "covered" },
  { service: "sessions-management", method: "POST", path: "/v1/sessions/import", controller: "import-controller", status: "covered" },
  { service: "testing-service", method: "DELETE", path: "/api/v1/endpoint-mocks", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "DELETE", path: "/api/v1/endpoint-mocks/call", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "DELETE", path: "/api/v1/endpoint-mocks/{id}", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "DELETE", path: "/api/v1/test-cases", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "DELETE", path: "/api/v1/test-cases/{id}", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "DELETE", path: "/api/v1/tests-runs", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "DELETE", path: "/api/v1/tests-runs/{id}", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "GET", path: "/api/v1/endpoint-mocks/call", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "GET", path: "/api/v1/endpoint-mocks/{id}", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "GET", path: "/api/v1/mode", controller: "service", status: "covered" },
  { service: "testing-service", method: "GET", path: "/api/v1/test-case-runs/{id}", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "GET", path: "/api/v1/test-case-runs/{id}/errors", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "GET", path: "/api/v1/test-cases/{id}", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "GET", path: "/api/v1/tests-runs/{id}", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "HEAD", path: "/api/v1/endpoint-mocks/call", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "PATCH", path: "/api/v1/endpoint-mocks/call", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/endpoint-mocks", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/endpoint-mocks/call", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/endpoint-mocks/create", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/endpoint-mocks/export", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/endpoint-mocks/import", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/endpoint-mocks/{id}", controller: "endpoint-mocks", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-case-runs", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-case-runs/cancel", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-case-runs/errors/export", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-case-runs/export", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-case-runs/{id}/cancel", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-case-runs/{id}/export", controller: "test-case-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-cases", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-cases/create", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-cases/export", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-cases/import", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/test-cases/{id}", controller: "test-cases", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/tests-runs", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/tests-runs/cancel", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/tests-runs/create", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/tests-runs/export", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/tests-runs/{id}/cancel", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "POST", path: "/api/v1/tests-runs/{id}/export", controller: "tests-runs", status: "covered" },
  { service: "testing-service", method: "PUT", path: "/api/v1/endpoint-mocks/call", controller: "endpoint-mocks", status: "covered" },
];
