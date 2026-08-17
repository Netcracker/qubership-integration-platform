# Egress gateway routes design

## Context

`engine` registers routes for deployed integration chains with the control
plane through `ControlPlaneService`. `docs/superpowers/specs/2026-07-31-istio-routes-registration-service-design.md`
added `IstioRoutesRegistrationService`, a Gateway API-native implementation
covering the two ingress tiers (`postPublicEngineRoutes`,
`postPrivateEngineRoutes`, `removeEngineRoutes`), but explicitly deferred
`postEgressGatewayRoutes` as a non-goal — it throws
`UnsupportedOperationException` today. `runtime-catalog`'s
`HttpRouteResourceBuilder` mirrors the same two ingress tiers for build-time
CR generation and has no egress counterpart either.

This spec covers that gap: a real Istio-native implementation of egress
routing, in both `engine` (live registration at deploy time) and
`runtime-catalog` (build-time CR generation), following the ingress
implementation's own conventions wherever they apply.

### How QIP dispatches an outgoing call today

An element that calls an external system (a Service Call element referencing
a `System`/`Environment`, or an HTTP/GraphQL sender element with an inline
URI) never targets the external host directly. `EndpointHelperSource` and
`RoutesGetterService` route it through a fixed internal gateway host
(`qip.gateway.egress.url`, e.g. `egress-gateway:8080`) plus a path prefix
that identifies which configured target to reach — `/system/{elementId}` for
Service Call elements, `/{senderType}/{elementId}/{hash}` for senders. The
actual external destination is resolved server-side: today by cloud-core's
`ControlPlaneDefaultService.postEgressGatewayRoutes`, which this spec
replaces with an Istio-native equivalent. The call-site contract
(`template.hbs`, `EndpointHelperSource`) doesn't change.

`RoutesGetterService` already builds a `DeploymentRouteUpdate`/`DeploymentRoute`
per such element, of type `EXTERNAL_SENDER` (senders) or `EXTERNAL_SERVICE`
(Service Call against an `EXTERNAL`-type system), carrying everything this
design needs:

- `path` — the resolved target's base URL (`scheme://host[:port][/path]`).
  For senders, host/port only (`SimpleHttpUriUtils.extractProtocolAndDomainWithPort`
  strips the path). For Service Call, the System's active environment
  address, which may include a path.
- `gatewayPrefix` — the internal path described above. Already fully
  resolved by the time either `engine` or `runtime-catalog` sees it: never
  contains `{placeholder}` syntax.
- `connectTimeout`.

### `endpoint` is shared across every chain a pod hosts

The ingress spec's "`endpoint` is not per-chain" section applies here
unchanged: `applicationConfiguration.getDeploymentName()` (`cloudServiceName`)
is one fixed value per engine pod, not per chain, and a pod can host more
than one chain. The two ingress `HTTPRoute` CRs are shared across every
chain the pod hosts, not one pair per chain, and `runtime-catalog`'s
`HttpRouteResourceBuilder` mirrors that: one public/one private CR per
build ("micro-domain"), aggregating rules across every `Snapshot` in that
build, not per individual chain.

Egress follows the same model: `<cloudServiceName>-egress-routes` is a
third, shared HTTPRoute, requiring the same merge-preserving-other-chains'-rules
treatment the two ingress tiers already implement.

## Goals

- A real `postEgressGatewayRoutes` in `IstioRoutesRegistrationService`,
  backed by Gateway API `HTTPRoute` plus Istio `ServiceEntry`/`DestinationRule`.
- A new `runtime-catalog` `ResourceBuilder`, structurally mirroring
  `HttpRouteResourceBuilder`, emitting the equivalent CRs at build time.
- Reuse of the alpha Gateway API `backendRefs: kind: Hostname` extension
  (`group: networking.istio.io`) to reference a `ServiceEntry`-registered
  external host directly from an `HTTPRoute` rule, per the CR shapes agreed
  during design.

## Non-goals

- **`enableInsecureTls`.** The prior spec already flagged this per-route
  TLS-skip-verify override as having no Gateway API equivalent. This design
  doesn't invent one: a `DestinationRule` originates TLS with standard
  certificate validation (`mode: SIMPLE`) whenever the target is `https`,
  full stop. A target relying on `enableInsecureTls` today needs its
  certificate trusted properly before this migration, or its own follow-up
  design.
- **`ServiceEntry`/`DestinationRule` cleanup.** These are shared across
  every chain that targets a given external host (see "One CR per external
  host" below) — deleting one on a single chain's undeploy would break every
  other chain still using that host. Neither `engine` nor `runtime-catalog`
  deletes them; undeploying the last chain that used a host leaves its
  `ServiceEntry`/`DestinationRule` in place, inert. Reference-counted cleanup
  is a possible follow-up, not part of this design.
- **Non-HTTP(S) external systems.** `route.getPath()` is parsed as an HTTP(S)
  URL, matching `ControlPlaneDefaultService`'s existing assumption. A System
  configured with a non-HTTP protocol was never routed through the egress
  gateway path in the first place.
- **The alpha Gateway API feature gate.** `backendRefs: kind: Hostname`
  requires `PILOT_ENABLE_ALPHA_GATEWAY_API=true` on `istiod`. Enabling it is
  an ops/Helm concern, out of scope for the application code this spec
  covers.
- `micro-engine`.

## Design

### 1. `EgressTarget`: parsing `route.getPath()`

Neither module's `SimpleHttpUriUtils` currently splits a URL into its parts.
A small helper, duplicated between `engine` and `runtime-catalog` under
`util.paths` next to `GatewayPathMatch` (same duplication precedent, no
shared library between the two modules for this class of helper):

```java
public record EgressTarget(String scheme, String host, int port, String path) {
    public static EgressTarget parse(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
        String path = StringUtils.isNotEmpty(uri.getPath()) ? uri.getPath() : "/";
        return new EgressTarget(scheme, uri.getHost(), port, path);
    }

    public boolean isHttps() {
        return "https".equals(scheme);
    }
}
```

This mirrors `ControlPlaneDefaultService.postEgressGatewayRoutes`'s existing
inline `URI.create(targetURL)` handling exactly (same default-port and
default-path behavior), just factored out so both new implementations share
one definition instead of re-deriving it.

### 2. `ControlPlaneService` interface change

```java
void postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint);
```

replacing today's `postEgressGatewayRoutes(DeploymentRouteUpdate route)`,
matching `postPublicEngineRoutes`/`postPrivateEngineRoutes` exactly: one
merge per deploy instead of one K8s read-modify-write round trip per route,
and `endpoint` gives `IstioRoutesRegistrationService` the `cloudServiceName`
it needs to name the shared egress CR.

All three implementers of `engine`'s `ControlPlaneService` need the signature
change:

- **`IstioRoutesRegistrationService`** — real implementation, see below.
- **`ControlPlaneDefaultService`** (cloud-core) — gains the `endpoint`
  parameter (unused, since Core Mesh's route objects aren't named per-pod)
  and loops internally, calling its existing per-route logic unchanged for
  each entry.
- **`ControlPlaneDevService`** — updated to match the new signature (no-op
  body, same as its other three methods).

`micro-engine` has its own, entirely separate `ControlPlaneService` interface
(same package name, different module, no compile-time relationship — it
takes `RouteRegistrationInfo`, not `DeploymentRouteUpdate`) and its own
`ControlPlaneServiceImpl`. It's unaffected by this change, consistent with
the ingress spec's existing non-goal excluding `micro-engine` entirely.

Call site, `RegisterRoutesInControlPlaneAction.execute()`: today's
`.forEach(route -> controlPlaneService.postEgressGatewayRoutes(formatServiceRoutes(route)))`
becomes a single batched call —

```java
controlPlaneService.postEgressGatewayRoutes(
    deploymentConfiguration.getRoutes().stream()
        .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
            || route.getType() == RouteType.EXTERNAL_SERVICE)
        .map(RegisterRoutesInControlPlaneAction::formatServiceRoutes)
        .toList(),
    applicationConfiguration.getDeploymentName());
```

reusing the same `applicationConfiguration.getDeploymentName()` value already
passed to `postPublicEngineRoutes`/`postPrivateEngineRoutes` in the same
method — no new plumbing.

### 3. `IstioRoutesRegistrationService`: the egress tier

**`HTTPRoute`:** named `<cloudServiceName>-egress-routes`, `parentRefs`
pointing at the fixed platform name `egress-gateway` (same pattern as the
`PUBLIC_GATEWAY_NAME`/`PRIVATE_GATEWAY_NAME` constants), same namespace as
the public/private CRs. Merged with the existing `mergeTierRoutes`/
`attemptMergeTierRoutes` machinery — generalized to take a rule-building
function so egress reuses the read-modify-write-with-retry skeleton (fetch,
diff by touched `GatewayPathMatch`, preserve untouched rules, delete when
empty, retry on `KubeApiConflictException` up to three times) instead of
duplicating it.

**Per-rule mapping**, one `HTTPRouteRule` per `DeploymentRouteUpdate`:

- `matches`: `GatewayPathMatch.forPath(route.getGatewayPrefix())` — always
  resolves to `PathPrefix`, since `gatewayPrefix` never contains
  `{placeholder}` syntax. This is also the rule's identity for the merge
  diff, exactly like the ingress tiers.
- `filters`: one `URLRewrite` filter, `hostname: target.host()`,
  `path: {type: ReplacePrefixMatch, replacePrefixMatch: target.path()}`.
- `backendRefs`: `kind: Hostname`, `group: networking.istio.io`,
  `name: target.host()`, `port: target.port()`, `weight: 1`.
- `timeouts.request`: from `route.getConnectTimeout()`, same
  `GatewayDuration` formatting the ingress tiers already use.

where `target = EgressTarget.parse(route.getPath())`.

**`ServiceEntry`/`DestinationRule`: one pair per unique external host.**
Unlike the `HTTPRoute` tiers, these aren't shared-CR merges — their entire
content is deterministic from the host, so any chain that touches a given
host can safely upsert the whole object, no read-modify-write or retry
needed.

- Name: `sanitize(lowercase(host)) + "-" + sha1hex(host).substring(0, 8)`.
  The hash suffix guarantees two different hosts never collide after
  sanitization strips characters a Kubernetes name can't contain (matching
  `RoutesGetterService.getEncodedURL`'s existing use of a hash suffix for
  the same reason). `sanitize` follows `K8sNameValidator`'s existing rule
  (strip everything outside `[-a-z0-9]`) — the input must already be
  lowercased first, since that validator doesn't lowercase and would
  otherwise silently drop uppercase characters instead of case-folding them.
- Namespace: same as the `HTTPRoute` CRs.
- `ServiceEntry`: `hosts: [host]`, one port
  (`number: target.port()`, `protocol: target.isHttps() ? "HTTPS" : "HTTP"`,
  `name` matching the protocol), `resolution: DNS`, `location: MESH_EXTERNAL`.
- `DestinationRule`: created only when `target.isHttps()`. `host: <host>`,
  `trafficPolicy.portLevelSettings[0]`: `port.number: target.port()`,
  `tls.mode: SIMPLE`, `tls.sni: host`.

`KubeCustomObjectRequest` already takes an arbitrary group/version/plural,
so no new `KubeOperator` capability is needed — just
`group: networking.istio.io`, `version: v1`,
`resourceNamePlural: serviceentries` / `destinationrules`.

### 4. `runtime-catalog`: a new `EgressRouteResourceBuilder`

Mirrors `HttpRouteResourceBuilder` structurally: same
`ResourceBuildContext<List<Snapshot>>`, same `collectRoutes` pattern via
`RoutesGetterService`/`DeploymentRouteMapper` (already returns
`EXTERNAL_SENDER`/`EXTERNAL_SERVICE` routes today — `HttpRouteResourceBuilder`
just doesn't currently look at them), filtering for those two types instead
of the trigger types.

- Emits the `<name>-egress-routes` `HTTPRoute` the same way
  `HttpRouteResourceBuilder.appendTier` does, reusing
  `preservedRulesFromCache`'s cache-key pattern (a new
  `EGRESS_HTTP_ROUTE_CACHE_KEY`) for the same redeploy-preserves-untouched-rules
  behavior.
- Emits one `ServiceEntry`/`DestinationRule` YAML document per unique host
  among the collected routes, using `EgressTarget` and the naming scheme
  from §3. Reuses the existing `K8sNameValidator` bean directly rather than
  reimplementing sanitization.

### 5. `CustomResourceService` extensions

- `deleteHttpRoutes(name)` also deletes `<name>-egress-routes`, alongside
  the existing public/private CR deletion, on full chain undeploy.
- `deleteChainSnapshotHttpRoutes(name, snapshotId)` also strips the egress
  rules that snapshot owned, reusing `tierOwnPaths`/`stripPathsFromTier`
  with a third tier — same `GatewayPathMatch`-based diffing already used for
  public/private.
- `ServiceEntry`/`DestinationRule` are untouched by both methods, per the
  cleanup non-goal above.
- `init()` registers `ServiceEntry` and `DestinationRule`
  (`networking.istio.io/v1`) as generic `KubeCustomObject`/
  `KubeCustomObjectList` types, alongside the existing `HTTPRoute`
  registration, so `deploy()`'s `Yaml.loadAll(resourceText)` can deserialize
  them.

### 6. Testing

Following the ingress spec's testing approach:

- `EgressTarget.parse` — default port per scheme, explicit port preserved,
  default path `/`, path preserved when present.
- `IstioRoutesRegistrationService.postEgressGatewayRoutes` — merge into an
  empty/absent CR; merge alongside another chain's existing rules (survive
  untouched); re-registering an already-present `gatewayPrefix` (no
  duplicate); empty given list against a CR with other chains' rules (CR not
  deleted, no-op); CR emptied entirely (delete called).
- `ServiceEntry`/`DestinationRule` upsert — one pair created for an `https`
  target; only a `ServiceEntry` (no `DestinationRule`) for `http`; two
  routes sharing a host converge on one CR pair, not two; name sanitization
  and hash-suffix collision avoidance for two different hosts that
  sanitize to the same base string.
- `EgressRouteResourceBuilder` — same shape as `HttpRouteResourceBuilderTest`,
  plus the per-host `ServiceEntry`/`DestinationRule` emission cases above.
- `CustomResourceService` — `deleteHttpRoutes`/`deleteChainSnapshotHttpRoutes`
  cover the egress CR the same way they already cover public/private;
  `ServiceEntry`/`DestinationRule` are asserted untouched by both.
