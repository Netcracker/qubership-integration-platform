/**
 * The `/api/` prefix table, derived from `infrastructure/nginx/routes.conf`.
 *
 * Addressing is two surfaces, not one. The `/api/` surface goes through nginx; chain invocation
 * does not, because no `location` matches `/routes/` or `/qip-routes/` and the request falls
 * through to the SPA and answers **200 with `index.html`**. A runtime spec that called a chain
 * through the proxy could not fail. So `Env` exposes two resolvers and this file backs the first.
 *
 * A spec cannot build an `/api/` path by concatenation either. `routes.conf` carries fourteen
 * `location` blocks whose rewrites disagree, so the shape depends on the resource: measured, the
 * form the UI builds — `/api/v1/qip/catalog/{rest}` — reaches `folders`, `chains` and `library` and
 * answers **404** for `systems` and `common-variables`. The general form is the catch-all at
 * `routes.conf:145`, which strips one segment and proxies the rest to the catalog, so
 * `/api/qip` + the service path reaches every catalog endpoint including `/v3/api-docs`.
 *
 * Two irregular shapes are worth knowing rather than deriving:
 *
 * - `routes.conf:46` matches greedily on the **last** `/catalog/`, so `/v1/catalog/runtime-deployments`
 *   is addressed as `/api/v1/qip/catalog/catalog/runtime-deployments` — the segment doubles.
 * - `routes.conf:64` intercepts `v2/snapshots` and rewrites it to `/v2/catalog/snapshots`, which the
 *   catch-all would not have done.
 *
 * The table matters because the Helm chart keeps a **separately maintained copy** of this config in
 * `charts/ui/templates/config.yaml` and nothing enforces the correspondence.
 *
 * One consumer deliberately does not use this table: the operation registry reads `/v3/api-docs` on
 * each service's own port. Both forms reach the catalog's document, but the engine's and
 * sessions-management's are not proxied at all, so the registry would need two rules for one job.
 */
import type { ServiceRole } from "./index.js";

/** How the proxy is expected to answer a row. */
export type RouteOutcome =
  /** The proxy and the service's own port answer alike — the row is a working address. */
  | "proxied"
  /** The proxy answers 404 by design, though the service does serve the path. */
  | "blocked";

export interface ApiRoute {
  /** The `location` in `infrastructure/nginx/routes.conf` this row exercises, by line number. */
  line: number;
  /** Which service the row reaches, for `proxied` rows. */
  role: ServiceRole;
  /** The path under the proxy's `/api/` surface. */
  api: string;
  /** The path the same request reaches on the service's own port. */
  service: string;
  outcome: RouteOutcome;
  note?: string;
}

/**
 * One row per `location` a spec can address, each measured against the running stack.
 *
 * Paths are chosen to be safe to `GET`: a POST-only endpoint answers 405 through both surfaces,
 * and 405 on both is exactly as good a proof that the rewrite reached the service as 200 on both.
 * What it must never be is 200 `text/html`, which is the SPA answering for a route nobody claimed.
 */
export const API_ROUTES: ApiRoute[] = [
  {
    line: 19,
    role: "runtime-catalog",
    api: "/api/v1/qip/catalog/chains/00000000-0000-0000-0000-000000000000/deployments",
    service: "/v1/catalog/chains/00000000-0000-0000-0000-000000000000/deployments",
    outcome: "proxied",
    note: "chain deployments and snapshots keep the /catalog/ segment on the way through",
  },
  {
    line: 28,
    role: "runtime-catalog",
    api: "/api/v1/qip/catalog/chains/roles",
    service: "/v1/catalog/chains/roles",
    outcome: "proxied",
    note: "GET is not mapped, so both surfaces answer 405 application/problem+json",
  },
  {
    line: 37,
    role: "runtime-catalog",
    api: "/api/v1/qip/catalog/folders",
    service: "/v1/folders",
    outcome: "proxied",
    note: "the form the UI builds; it strips /catalog/ and covers only six resource families",
  },
  {
    line: 46,
    role: "runtime-catalog",
    api: "/api/v1/qip/catalog/catalog/runtime-deployments",
    service: "/v1/catalog/runtime-deployments",
    outcome: "proxied",
    note: "irregular: the greedy match on the last /catalog/ doubles the segment",
  },
  {
    line: 55,
    role: "runtime-catalog",
    api: "/api/qip/v1/maas-actions/kafka",
    service: "/v1/maas-actions/kafka",
    outcome: "proxied",
  },
  {
    line: 64,
    role: "runtime-catalog",
    api: "/api/qip/v2/snapshots/bulk-delete",
    service: "/v2/catalog/snapshots/bulk-delete",
    outcome: "proxied",
    note: "irregular: v2/snapshots is rewritten to /v2/catalog/snapshots, which the catch-all is not",
  },
  {
    line: 73,
    role: "runtime-catalog",
    api: "/api/v1/qip/variables-management/common-variables",
    service: "/v1/common-variables",
    outcome: "proxied",
  },
  {
    line: 82,
    role: "runtime-catalog",
    api: "/api/qip/v1/folders",
    service: "/v1/folders",
    outcome: "proxied",
    note: "same target as line 37 by a different rule; both are live and both are load-bearing",
  },
  {
    line: 91,
    role: "runtime-catalog",
    api: "/api/qip/v1/import/system",
    service: "/v1/import/system",
    outcome: "proxied",
    note: "GET on the import endpoint answers 400 on both surfaces",
  },
  {
    line: 100,
    role: "runtime-catalog",
    api: "/api/v1/qip/systems-catalog/systems",
    service: "/v1/systems",
    outcome: "proxied",
  },
  {
    line: 109,
    role: "sessions-management",
    api: "/api/v1/qip/sessions-management/sessions",
    service: "/v1/sessions",
    outcome: "proxied",
  },
  {
    line: 118,
    role: "engine",
    api: "/api/v1/qip/engine/live-exchanges",
    service: "/v1/engine/live-exchanges",
    outcome: "proxied",
    note: "the engine keeps its /engine/ segment on the way through, unlike every other service",
  },
  {
    line: 130,
    role: "testing-service",
    api: "/api/v1/qip/testing-service/endpoint-mocks/call",
    service: "/api/v1/endpoint-mocks/call",
    outcome: "blocked",
    note: "the engine calls endpoint mocks from inside the network; the proxy returns 404 on purpose",
  },
  {
    line: 136,
    role: "testing-service",
    api: "/api/v1/qip/testing-service/swagger/doc.json",
    service: "/api/v1/swagger/doc.json",
    outcome: "proxied",
    note: "the testing service serves /api/v1/, so this rule keeps the prefix instead of stripping it",
  },
  {
    line: 145,
    role: "runtime-catalog",
    api: "/api/qip/v1/systems",
    service: "/v1/systems",
    outcome: "proxied",
    note: "the catch-all, and the only general form: it reaches every catalog path",
  },
];

/**
 * Shapes that reach the service directly and 404 through the proxy.
 *
 * They are in the table because the failure is silent otherwise: the UI's form works for the six
 * families at `routes.conf:37` and a spec that generalises from `folders` to `systems` gets a 404
 * it will read as the platform's answer.
 */
export const NOT_PROXIED: Array<{ api: string; service: string; why: string }> = [
  {
    api: "/api/v1/qip/catalog/systems",
    service: "/v1/systems",
    why: "systems is not one of the six families the /catalog/ rule at routes.conf:37 lists",
  },
  {
    api: "/api/v1/qip/catalog/common-variables",
    service: "/v1/common-variables",
    why: "common-variables is reached through variables-management or the catch-all, not /catalog/",
  },
];

/**
 * `/api/` paths that answer 404 because `location /api/` at `routes.conf:158` claims them.
 *
 * A prefix location loses to every regex above it and wins only when none matched, so this is the
 * guard against the SPA answering 200 `text/html` for a path no service serves.
 */
export const UNCLAIMED_API_PATH = "/api/v1/nonsense";

/**
 * Chain invocation, which the proxy does not serve.
 *
 * Measured: `GET localhost:8080/routes/x` answers **200 `text/html`** — the SPA — while the engine
 * answers 404. Kept here beside the table because the two are one subject, and because a spec that
 * reaches for a base URL should meet this note before it reaches for the wrong one.
 *
 * The engine serves a deployed chain on **two** prefixes, not one.
 * `CamelServletConfiguration.camelServlet` maps the Camel servlet on both
 * `CAMEL_ROUTES_LEGACY_PREFIX` (`/routes`) and `cip.camel.routes.prefix`, which resolves through
 * `app.prefix` to `/qip-routes`. Measured against a seeded chain on this stack: `POST` answered
 * **200** on `/routes/{contextPath}` and on `/qip-routes/{contextPath}` alike, `GET` answered
 * **405** on both while the route was live, and a path no chain owns answered **404** on both. It
 * makes no difference whether the chain declares `externalRoute` — an `externalRoute: false`
 * fixture answers on `/qip-routes` too, so that flag does not decide the prefix.
 *
 * The suite addresses `/routes` because it is a compiled-in constant, where the other form is
 * `app.prefix` interpolated and moves with configuration. Neither ends in a slash, and a trailing
 * one changes nothing: `/routes/{contextPath}/` answered 200 as well.
 */
export const CHAIN_ROUTE_PREFIX = "/routes";

/**
 * The `/api/` path that reaches `servicePath` on `role`.
 *
 * The catalog goes through the catch-all, which is the one form that reaches all of it. Every other
 * service needs its own segment, because the catch-all proxies to the catalog alone. All four are
 * addressed by their version segment, and a path carrying none is refused here rather than handed
 * out as a URL no `location` claims.
 */
export function apiPath(role: ServiceRole, servicePath: string): string {
  const path = servicePath.startsWith("/") ? servicePath : `/${servicePath}`;

  // The testing service's own paths start `/api/v1/`, so the version is looked for anywhere in the
  // first two segments rather than only at the head.
  const version = /^\/(?:api\/)?(v\d+)\//.exec(path)?.[1];
  if (!version) {
    throw new Error(
      `${role} paths are addressed by version segment and ${JSON.stringify(servicePath)} has none`,
    );
  }
  if (role === "runtime-catalog") {
    // The catch-all is `^/api/[^/]+/(v\d+)/`, so the version is as load-bearing here as it is for
    // the other three: a catalog path without one matches no `location`, `location /api/` claims it
    // and nginx answers 404. The check above is what keeps a spec from being handed that address.
    return `/api/qip${path}`;
  }
  if (role === "engine") {
    // The engine's rule rewrites to /$1/engine/$2, so its own paths already carry the segment.
    if (!path.startsWith(`/${version}/engine/`)) {
      throw new Error(`engine paths start with /${version}/engine/, got ${servicePath}`);
    }
    return `/api/${version}/qip/engine/${path.slice(`/${version}/engine/`.length)}`;
  }
  if (role === "testing-service") {
    // The testing service serves under /api/ itself, and its rule keeps the prefix.
    if (!path.startsWith(`/api/${version}/`)) {
      throw new Error(`testing-service paths start with /api/${version}/, got ${servicePath}`);
    }
    return `/api/${version}/qip/testing-service/${path.slice(`/api/${version}/`.length)}`;
  }
  return `/api/${version}/qip/sessions-management/${path.slice(`/${version}/`.length)}`;
}
