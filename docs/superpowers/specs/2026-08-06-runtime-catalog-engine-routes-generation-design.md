# HTTPRoute CRs for micro-engine's own endpoints — design

## Context

`RoutesRegistrator` (`micro-engine`) registers micro-engine's own REST endpoints with
Cloud-Core Mesh control-plane on startup: `/sessions` and `/chains/{chainId}` each in
PUBLIC, PRIVATE, and INTERNAL, plus `/live-exchanges` in PUBLIC only. Under Istio,
micro-domain deployments have no live control-plane self-registration — the same gap the
chain-routes feature closed (see
[2026-08-05-runtime-catalog-httproute-generation-design.md](2026-08-05-runtime-catalog-httproute-generation-design.md)).
`runtime-catalog` must generate the equivalent `HTTPRoute` CRs at deploy time so
micro-engine's own endpoints stay reachable under Istio.

This is a separate concern from the chain-routes feature: those CRs carry per-chain
trigger routes that change on every chain deploy/undeploy. Micro-engine's own routes are
fixed — a pure function of the domain name — so this feature needs no merge-with-cluster-
state logic and no per-snapshot cleanup.

## Goals

- Generate `HTTPRoute` CRs for micro-engine's own endpoints (sessions, checkpoint
  sessions, live exchanges) on every micro-domain build.
- Reuse the gateway-cascade and `parentRefs` conventions already established elsewhere in
  this migration (`httproute-from-code` and `core-mesh-crs-to-gatewayapi` skills), so the
  generated CRs are consistent with how the rest of the Istio migration models
  public/private/internal gateway reachability.
- Clean up the generated CRs when a micro-domain is deleted.

## Non-goals

- Merging with existing cluster state. These routes never change between builds for a
  given domain, so there's nothing to preserve.
- Per-chain-snapshot cleanup. These CRs are untouched by individual chain
  deploy/undeploy.
- Changing `RoutesRegistrator` or any Cloud-Core Mesh registration behavior. This feature
  only adds the Istio-side generation path.

## Design

### 1. Gateway-cascade model

`RouteType` (from `route-registration-common`) cascades: a PUBLIC route is reachable via
the public, private, and internal gateways; a PRIVATE route via private and internal; an
INTERNAL route via internal only. The generated CRs represent this with one CR per
`RouteType`, where each CR holds only its own type's rules but lists every gateway that
type cascades to in `parentRefs` — the same model the `httproute-from-code` and
`core-mesh-crs-to-gatewayapi` skills already use elsewhere in this migration:

| CR (name suffix) | `parentRefs` | rules |
|---|---|---|
| `-public-routes` | `public-gateway` (Gateway), `private-gateway` (Gateway), `internal-gateway-service` (Service) | sessions, chains/, live-exchanges |
| `-private-routes` | `private-gateway` (Gateway), `internal-gateway-service` (Service) | sessions, chains/ |
| `-internal-routes` | `internal-gateway-service` (Service) | sessions, chains/ |

`public-gateway` and `private-gateway` use `group: gateway.networking.k8s.io`,
`kind: Gateway`. `internal-gateway-service` uses `group: ''`, `kind: Service` — the same
literal name and shape the `core-mesh-crs-to-gatewayapi` skill's
`route-configuration-mapping.md` already documents for the internal tier.

### 2. Route table

The route table below is a hardcoded mirror of
`RoutesRegistrator.registerRoutes()`. It isn't derived from that class at build or run
time — a future change to `RoutesRegistrator`'s registered endpoints must be reflected
here by hand.

| apiPath | from (gateway-facing path) | to (backend rewrite path) |
|---|---|---|
| `/sessions` | `<publicRoutePrefixV1>/<domain>/sessions` | `/v1/engine/sessions` |
| `/chains/{chainId}` (truncated to `/chains/`) | `<publicRoutePrefixV1>/<domain>/chains/` | `/v1/engine/chains/` |
| `/live-exchanges` | `<publicRoutePrefixV1>/<domain>/live-exchanges` | `/v1/engine/live-exchanges` |

Two deliberate departures from `RoutesRegistrator`'s literal behavior:

- **Checkpoint-session path.** `CheckpointSessionController`'s real path is
  `/chains/{chainId}`, a JAX-RS path template. Gateway API's `PathPrefix` match type is a
  literal prefix with no `{var}` templating, so the rule's `from`/`to` both truncate to
  `/chains/` — the segment before the variable. `ReplacePrefixMatch` preserves everything
  after the matched prefix, so the actual chain ID passes through unchanged.
- **Live-exchanges rewrite.** `RoutesRegistrator` registers this route with `to == from`
  (via `RouteEntry`'s 2-arg constructor), which leaves the domain segment in the backend
  path. `LiveExchangesController`'s real `@Path` is the fixed `/v1/engine/live-exchanges`,
  with no domain segment — so a literal `to == from` mirror wouldn't reach the controller.
  The generated rule rewrites to `/v1/engine/live-exchanges` instead, matching what the
  controller actually serves.

`domain` is `context.getBuildInfo().getOptions().getName()`, used **raw** — the same
unsanitized value `CamelKIntegrationResourceBuilder` already writes into the
`QIP_ENGINE_DOMAIN` environment variable, so it matches what `EngineInfo.getDomain()`
reports inside the running micro-engine container. (This is deliberately not run through
`K8sNameValidator` — that sanitization is for Kubernetes object names, not URL path
segments.)

`publicRoutePrefixV1` is a new `runtime-catalog` config property,
`qip.control-plane.routes.public.v1-prefix`, defaulting to `/api/v1/qip/engine` — the
same value micro-engine's own `application.yml` resolves for
`qip.control-plane.routes.public.v1-prefix`.

Every rule's `backendRefs` points at the same Service `ServiceResourceBuilder` already
generates for the domain (`serviceNamingStrategy`, port 8080, `weight: 1`) — that Service
fronts the domain's Integration container, which is micro-engine itself.

### 3. New components

**`EngineRoutesResourceBuilder`** (`org.qubership.integration.platform.runtime.catalog.cr.builders`,
alongside `ServiceResourceBuilder` and `ServiceMonitorBuilder` — this builder is
domain-level, not chain-dependent, so it doesn't belong under `cr.builders.chain` where
`HttpRouteResourceBuilder` lives). Implements `ResourceBuilder<List<Snapshot>>`:

- `enabled()` always returns `true` — micro-engine always exposes these endpoints; there's
  no domain option that turns this off.
- `build()` renders the shared Handlebars template once per tier (public, private,
  internal) and concatenates the three documents, the same way
  `HttpRouteResourceBuilder.build()` concatenates its two tiers today.

**`cr/templates/engine-routes.hbs`** — one shared Handlebars template parameterized by
`parentRefs[]` and `rules[]`, rendered three times with different data per tier:

```hbs
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: {{name}}
  labels:
    {{domainLabel}}: {{domainName}}
    {{bgVersionLabel}}: {{bgVersion}}
spec:
  parentRefs:
{{#each parentRefs}}
  - group: '{{group}}'
    kind: {{kind}}
    name: {{name}}
{{/each}}
  rules:
{{#each rules}}
  - matches:
    - path:
        type: PathPrefix
        value: {{path}}
    filters:
    - type: URLRewrite
      urlRewrite:
        path:
          type: ReplacePrefixMatch
          replacePrefixMatch: {{rewritePath}}
    backendRefs:
    - group: ''
      kind: Service
      name: {{backendServiceName}}
      port: 8080
      weight: 1
{{/each}}
```

`group: '{{group}}'` is quoted so the internal tier's empty-string group renders as
`group: ''` rather than a bare, YAML-null-like empty value. `port`/`weight` are literal
constants in the template (every rule uses the same backend and weight), matching how
`service.hbs` already hardcodes its own port/protocol literals.

**Three new naming strategies** (`cr.naming.strategies`), same shape as
`HttpRoutePublicNamingStrategy`/`HttpRoutePrivateNamingStrategy` — delegate to
`integrationResourceNamingStrategy`, append a configurable suffix, and reserve headroom
for their own suffix before `K8sNameValidator`'s 63-character truncation so long domain
names can't collide two tiers into the same truncated name:

- `EngineRoutesPublicNamingStrategy` — `qip.cr.naming.engine-routes.public-suffix`,
  default `-public-routes`
- `EngineRoutesPrivateNamingStrategy` — `qip.cr.naming.engine-routes.private-suffix`,
  default `-private-routes`
- `EngineRoutesInternalNamingStrategy` — `qip.cr.naming.engine-routes.internal-suffix`,
  default `-internal-routes`

### 4. Lifecycle

`EngineRoutesResourceBuilder` is a common resource builder, so it's picked up
automatically by `CustomResourceBuildService`'s `List<ResourceBuilder<List<Snapshot>>>`
injection — no manual wiring needed there.

`CustomResourceService.delete(String name)` gains a `deleteEngineRoutes(name)` call,
alongside the existing `deleteHttpRoutes(name)` call, that unconditionally deletes all
three tier CRs by name (`kubeOperator.deleteCustomObject` already tolerates a 404
internally, so no existence check is needed first).

No other lifecycle hook is needed: these CRs carry no per-chain-snapshot content, so
`deleteChainSnapshot` doesn't touch them, and `getIntegrationResources`/
`CustomResourceBuildContextFactory` don't need to fetch or cache them for merge purposes.

## Testing

- **Naming strategy tests** (one per new strategy, mirroring
  `HttpRoutePublicNamingStrategyTest`): default suffix, configured suffix override, and
  the long-domain-name truncation-headroom case.
- **`EngineRoutesResourceBuilder` test**: asserts, per tier, the rendered rule count,
  `parentRefs` list, each rule's `path`/`rewritePath` values (including the
  `/chains/{chainId}` → `/chains/` truncation and the live-exchanges rewrite), and the
  backend Service reference.
- **`CustomResourceService` test addition**: `delete(name)` calls `deleteCustomObject`
  for all three engine-routes tier names.
