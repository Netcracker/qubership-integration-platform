# IstioRoutesRegistrationService design

## Context

`engine` registers the HTTP routes for deployed integration chains with the
Cloud-Core Mesh control plane through `ControlPlaneService`. As part of the
Core Mesh → Istio migration, `engine` needs an Istio-native implementation of
that same interface, one that manages Gateway API `HTTPRoute` custom
resources directly via the Kubernetes API instead of calling the Cloud-Core
Mesh V3 REST API.

This spec covers `engine` only. `micro-engine` has its own `ControlPlaneService`
implementation and is out of scope here — explicitly deferred to future work.

### Current interface

`ControlPlaneService` (already refactored ahead of this work) has four methods:

```java
void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint);
void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint);
void removeEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName);
void postEgressGatewayRoutes(DeploymentRouteUpdate route);
```

Two callers exist:

- `RegisterRoutesInControlPlaneAction` (runs `@OnBeforeDeploymentContextCreated`)
  calls `postPublicEngineRoutes`/`postPrivateEngineRoutes` with each tier's
  *complete current* route list for the deployment, then calls
  `removeEngineRoutes` with each cleanup-eligible route **re-tagged with the
  opposite tier's type** (`opposingTierRemovals`), to purge stale
  cross-tier registrations left over from a previous deploy where a route's
  visibility (public/private/internal) was different.
- `RemoveRoutesFromControlPlaneAction` (runs `@OnStopDeploymentContext`) calls
  `removeEngineRoutes` with each gateway-registered route **as-is** (same
  tier as its current type), to purge everything on full deployment stop.

Because both callers pre-encode which tier a route needs removing from (via
the route's `type` field, flipped or not), `removeEngineRoutes`'s
implementation never needs to know which caller invoked it or why — it only
needs to remove each given route from the tier its type indicates.

**Redeploy ordering:** on a chain redeployment, `RegisterRoutesInControlPlaneAction`
(register, on the *new* context) runs before `RemoveRoutesFromControlPlaneAction`
(stop, on the *old* context), not the reverse. `ChainRouteRegistry` handles the
resulting overlap: it tracks every currently-registered deployment per chain, and
a stop action removes only the paths no other registered deployment still claims.
A route dropped entirely from a chain (not moved to another tier, just deleted) is
still cleared correctly, since no other deployment claims it either. See
`docs/superpowers/specs/2026-08-03-chain-route-registry-design.md` and
`docs/superpowers/specs/2026-08-03-chain-route-registry-multi-registration-design.md`
for the full mechanism.

### `endpoint` is not per-chain

`applicationConfiguration.getDeploymentName()` — the value passed to every
`ControlPlaneService` call as `endpoint`/`deploymentName` — returns
`cloudServiceName` (`spring.application.cloud_service_name`), a single fixed
value for the whole engine pod. It does **not** vary per chain.
`RegisterRoutesInControlPlaneAction` runs once per chain deploy/undeploy, but
every chain hosted by this pod posts to the *same* `endpoint` value. Core
Mesh's control-plane API tolerates this because it upserts individual routes
by path rather than replacing a named object wholesale — posting chain A's
routes never wipes chain B's previously-posted routes under the same name.

This has a direct consequence for the Istio design below: the two `HTTPRoute`
CRs are shared across every chain this engine pod hosts, not one pair per
chain. `postPublicEngineRoutes`/`postPrivateEngineRoutes`/`removeEngineRoutes`
must each touch only the rules belonging to the paths they were given,
leaving every other chain's rules in the shared CR untouched.

## Goals

- Real implementations of `postPublicEngineRoutes`, `postPrivateEngineRoutes`,
  and `removeEngineRoutes`, backed by Gateway API `HTTPRoute` CRs.
- Switchable at runtime alongside the existing `ControlPlaneDefaultService` /
  `ControlPlaneDevService` split, via a new Spring property.
- Reuse `engine`'s existing (currently unused) `KubeOperator` /
  `KubeCustomObject` / `KubeCustomObjectRequest` Kubernetes plumbing rather
  than introducing new client wiring.

## Non-goals

- `postEgressGatewayRoutes` (routes to arbitrary external URLs for senders/
  services) is stubbed, not implemented. Plain Gateway API `HTTPRoute` can't
  represent an arbitrary external backend without a matching `ExternalName`
  Kubernetes `Service` (which nothing here creates), and the current
  `enableInsecureTls` per-route TLS override has no Gateway API equivalent —
  same category of gap the `core-mesh-crs-to-gatewayapi` skill already
  documents for `StatefulSession`/`rateLimit`/`circuitBreaker`. Needs its own
  design pass later.
- `micro-engine`.
- Any change to the *shape* of `ControlPlaneService`'s public contract — that
  refactor already happened; this spec only adds a new implementation.

## Design

### 1. Class and wiring

`IstioRoutesRegistrationService`, added next to `ControlPlaneDefaultService`/
`ControlPlaneDevService` in
`org.qubership.integration.platform.engine.cloudcore.controlplane`:

```java
@Component("controlPlaneService")
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class IstioRoutesRegistrationService implements ControlPlaneService
```

`ControlPlaneDefaultService` gains a second stacked `@ConditionalOnProperty`
too:

```java
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Core", matchIfMissing = true)
```

so the two are mutually exclusive on `mesh-type`, the same way they're
already mutually exclusive with `ControlPlaneDevService` on `enabled`. The new
property is bound in `application.yml` as
`qip.control-plane.mesh-type: ${SERVICE_MESH_TYPE:Core}`, so Helm/deployment
config keeps using the platform-wide `SERVICE_MESH_TYPE` env var convention
established by the migration guide, while the Java-side property name stays
consistent with the existing `qip.control-plane.*` namespace.

`IstioRoutesRegistrationService` depends on:
- `KubeOperator` (constructor-injected; already a Spring bean via
  `KubeOperatorConfiguration`).
- The same `@Value`-injected properties `ControlPlaneDefaultService` uses for
  namespace and path prefix: `cloud.microservice.namespace`,
  `qip.chains.external-routes.base-path`.

### 2. `KubeOperator` extensions

`KubeOperator` currently exposes `createOrReplaceCustomObject(...)` and, only
privately, a resource-version-only fetch. Two capabilities are missing and
needed here:

- `Optional<KubeCustomObject> getCustomObject(KubeCustomObjectRequest request)`
  — full fetch of a CR's current body; empty `Optional` on a 404.
- `void deleteCustomObject(KubeCustomObjectRequest request)` — delete a CR,
  treating 404 as already-gone (no-op, not an error).

Both follow the existing `KubeApiException` error-handling pattern already
used by `createOrReplaceCustomObject`.

### 3. HTTPRoute resource model

`KubeCustomObject.spec` is a loosely-typed `Map<String, Object>`, impractical
to hand-build for a structure as nested as an `HTTPRoute`
(`parentRefs[]`, `rules[].matches[]`, `rules[].filters[]`,
`rules[].backendRefs[]`, `rules[].timeouts`). This design adds a small POJO
package, `org.qubership.integration.platform.engine.model.gatewayapi`,
mirroring how `model.controlplane.v3.post` already models the Cloud-Core Mesh
wire format:

- `HTTPRouteSpec` (`parentRefs`, `rules`)
- `ParentReference` (`group`, `kind`, `name`)
- `HTTPRouteRule` (`matches`, `filters`, `backendRefs`, `timeouts`)
- `HTTPRouteMatch` / `PathMatch` (`type`, `value`)
- `HTTPBackendRef` (`group`, `kind`, `name`, `port`, `weight`)
- `URLRewriteFilter` (`type`, `replacePrefixMatch`)
- `HTTPRouteTimeouts` (`request`)

Conversion to/from `KubeCustomObject.spec`'s `Map<String, Object>` goes
through the existing `ObjectMapper`
(`objectMapper.convertValue(pojo, Map.class)` for building,
`objectMapper.convertValue(map, HTTPRouteSpec.class)` for reading back during
POST/REMOVE's read-modify-write).

**Naming:** `<cloudServiceName>-chain-public-routes` /
`<cloudServiceName>-chain-private-routes`, `apiVersion:
gateway.networking.k8s.io/v1`, `kind: HTTPRoute`, same namespace
`ControlPlaneDefaultService` uses today. `cloudServiceName` is the existing
`endpoint` value already passed into every `ControlPlaneService` call — no new
property wiring needed. One pair of CRs total per engine pod (shared across
every chain it hosts), not one pair per chain — see "`endpoint` is not
per-chain" above.

**`parentRefs`:** hardcoded well-known platform gateway names, matching the
`core-mesh-crs-to-gatewayapi` skill's convention — `public-gateway` /
`private-gateway` (`kind: Gateway`, `group: gateway.networking.k8s.io`). Not
configurable via `@Value`; these are fixed platform names, the same way
`PUBLIC_GATEWAY_SERVICE_NODEGROUP` is a `public static final` constant in
`ControlPlaneDefaultService` today rather than a property.

**`backendRefs`:** `kind: Service`, `name: <cloudServiceName>`, `port: 8080`,
`weight: 1` — identical target to what `ControlPlaneDefaultService.getRoute()`
builds today (`http://<endpoint>:8080`).

**Per-rule mapping**, one `HTTPRouteRule` per `DeploymentRouteUpdate`:
- `matches`: `[{path: {type: PathPrefix, value: baseRoutePrefix + route.getPath()}}]`
- `filters`: a `URLRewrite` filter rewriting to
  `CAMEL_ROUTES_PREFIX + route.getPath()` (mirrors today's `prefixRewrite`)
- `timeouts.request`: from `route.getConnectTimeout()` when set (positive),
  formatted as `"<milliseconds>ms"` (e.g. `"30000ms"`) per the
  `core-mesh-crs-to-gatewayapi` skill's Rule mapping
  (`timeout *int64 → timeouts.request: "<value>ms"`).
  Core Mesh's separate `idleTimeout` has no Gateway API `HTTPRoute`
  equivalent and is dropped, consistent with how the migration skill already
  treats fields with no Gateway API analogue elsewhere (flag and omit, don't
  invent a workaround).

**Rule identity:** a rule's identity within a tier's CR is its match path
(`baseRoutePrefix + route.getPath()`). This mirrors the identity Core Mesh's
own DELETE-by-UUID lookup already relies on (matched by path + host-rewrite,
not by any chain/deployment ID) — trigger paths are assumed unique within a
gateway tier regardless of which chain registered them.

**Field naming:** all Gateway API field names are camelCase (no snake_case
anywhere in `HTTPRoute`), so the new POJOs need no Jackson naming
configuration — plain Lombok classes with camelCase fields, the same pattern
`model.controlplane.v3.post` already uses with zero `@JsonProperty`
annotations (e.g. `RouteV3`, `Metadata`).

### 4. Shared merge operation

Both POST and REMOVE reduce to the same shape, since both touch a CR shared
across chains and must only affect rules matching the paths they were given:

```
mergeTierRoutes(tierRequest, givenRoutes, buildNewRules):
    current = kubeOperator.getCustomObject(tierRequest)          // Optional
    existingRules = current.map(rules).orElse(emptyList)
    touchedPaths = givenRoutes.map(r -> baseRoutePrefix + r.getPath()).toSet()
    preservedRules = existingRules.filter(rule -> rule.matchPath NOT IN touchedPaths)
    newRules = buildNewRules(givenRoutes)                        // built rules for POST, empty for REMOVE
    mergedRules = preservedRules + newRules
    if mergedRules.isEmpty():
        kubeOperator.deleteCustomObject(tierRequest)
    else if mergedRules != existingRules:
        kubeOperator.createOrReplaceCustomObject(tierRequest, spec.rules = mergedRules)
    // else: nothing changed, skip the API call
```

A private helper implements this once; `postPublicEngineRoutes`/
`postPrivateEngineRoutes` call it with `buildNewRules` = the per-rule mapping
from §3, `removeEngineRoutes` calls it (once per tier, after splitting by
`isPublicTriggerRoute`/`isPrivateTriggerRoute`) with `buildNewRules` = always
empty.

This means:
- An empty *input* list no longer implies deleting the CR — only an empty
  *merged* result does, so one chain having zero routes of a tier never
  clobbers another chain's rules in the shared CR.
- POST is upsert-by-path: a chain's own stale rules (e.g. from a retried
  POST) are replaced, not duplicated; other chains' rules are untouched
  because their paths aren't in `touchedPaths`.
- REMOVE is delete-by-path: matching rules drop out; everything else,
  including other chains' rules, is preserved.

### 5. Concurrency

`IstioRoutesRegistrationService` synchronizes its three public methods
(`postPublicEngineRoutes`, `postPrivateEngineRoutes`, `removeEngineRoutes`) on
a single lock, so only one merge operation runs at a time per pod. This
serializes every mutating CR call from one pod — including cases where
`postPublicEngineRoutes` and `postPrivateEngineRoutes` touch different tiers
and could otherwise run concurrently — which is an acceptable trade-off given
deploy/undeploy isn't a hot path.

Across pods, `mergeTierRoutes` uses Kubernetes' optimistic concurrency
correctly: it carries the resourceVersion observed by its own read into both
the write (`KubeOperator.createOrReplaceCustomObject`) and the delete
(`KubeOperator.deleteCustomObject`). A concurrent write from another replica
between the read and the write is rejected with an HTTP 409, not silently
overwritten. `mergeTierRoutes` retries the whole read-merge-write cycle
against fresh state up to three times on a genuine 409
(`KubeApiConflictException`). A conflict on the third attempt surfaces as
`ControlPlaneException`: `RegisterRoutesInControlPlaneAction` retries it via
`DeploymentRetriableException`, while `RemoveRoutesFromControlPlaneAction`
fails permanently via `RouteRegistrationException`.

The residual risk is contention under a rolling restart with many replicas:
each retry round-trips the Kubernetes API, so a busy CR can add latency to a
deploy or undeploy, but it no longer loses a write.

### 6. Error handling

`KubeApiException` (already thrown by `KubeOperator` on API failure) is
caught and rewrapped as `ControlPlaneException`, matching
`ControlPlaneDefaultService`'s existing contract, so
`RegisterRoutesInControlPlaneAction` / `RemoveRoutesFromControlPlaneAction`'s
existing `catch (ControlPlaneException e) → DeploymentRetriableException`
keeps working unchanged.

`postEgressGatewayRoutes` throws `UnsupportedOperationException` with a
message pointing at the open egress design gap (see Non-goals).

### 7. Testing

`ControlPlaneDefaultService` has no existing unit tests, but this class has
enough non-trivial branching (merge-vs-delete, tier splitting, preserving
untouched rules) to warrant coverage, and the parallel micro-engine work just
added equivalent coverage for its `ControlPlaneServiceImpl`/
`RouteRegistrationService` changes. Tests will mock `KubeOperator` and assert
the `KubeCustomObjectRequest` / `HTTPRouteSpec` shapes built for
create/replace/delete calls, covering at minimum:
- POST into an empty/absent CR → create-or-replace with just the new rules.
- POST alongside another chain's existing rules → those rules survive
  untouched in the merged result.
- POST that re-registers a path already present (retry) → no duplicate rule.
- POST with an empty given list, CR already has other chains' rules → CR is
  *not* deleted, create-or-replace not called (no-op, since nothing changed).
- REMOVE where the target CR doesn't exist → no-op, no exception.
- REMOVE that empties the CR entirely → delete called.
- REMOVE that leaves other chains' rules in place → create-or-replace called
  with just the remaining rules.
- `postEgressGatewayRoutes` → throws `UnsupportedOperationException`.

`KubeOperator`'s two new methods also get their own unit tests, independent of
`IstioRoutesRegistrationService`'s tests: `getCustomObject` returning a parsed
`KubeCustomObject` on a 200, empty `Optional` on a 404, and propagating
`KubeApiException` on other failures; `deleteCustomObject` succeeding on a 200
and treating a 404 as a no-op rather than an error.
