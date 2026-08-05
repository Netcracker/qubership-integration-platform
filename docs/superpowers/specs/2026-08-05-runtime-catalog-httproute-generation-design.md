# Generating Istio HTTPRoute CRs for chain routes in runtime-catalog

## Context

`runtime-catalog`'s `cr.builders` package (`org.qubership.integration.platform.runtime.catalog.cr.builders`)
generates the full set of Kubernetes resources for a **micro-domain** (chains deployed via
`CustomResourceController`/`CustomResourceService` as a dedicated Camel-K `Integration`, applied directly
to the cluster through the K8s API — as opposed to a **classic** domain, where chains are deployed to a
shared `engine` pool that self-registers routes at runtime through `IstioRoutesRegistrationService`).

Micro-domain chains currently have **no route-registration mechanism at all**. `RouteUnregisterAction` and
`RouteRegistrationService.unregisterRoutes` were removed from `micro-engine` earlier on this branch (restoring
pre-branch behavior, where `micro-engine` never dynamically registered or unregistered routes) on the explicit
understanding that this gap would be closed by having `runtime-catalog` generate the `HTTPRoute` CR statically,
alongside the `Integration`/`Service`/`ConfigMap` CRs it already builds — so no further changes to `micro-engine`
are needed.

The route data itself already exists: `RoutesGetterService.getRoutes(...)` returns `DeploymentRoute` entities
for a snapshot, `DeploymentRouteMapper.asUpdates(...)` maps them to `DeploymentRouteUpdate` (path,
gatewayPrefix, type, connectTimeout) — the same shape `engine`'s classic-domain path already sends over the
wire. `RouteInfoBeansBuilder` already uses this exact query to bake `RouteRegistrationInfo` beans into a
chain's Camel XML.

`engine`'s `IstioRoutesRegistrationService` (docs/superpowers/specs/2026-07-31-istio-routes-registration-service-design.md,
amended by docs/superpowers/specs/2026-08-03-chain-route-registry-multi-registration-design.md) is the reference
implementation for the classic-domain path: one `HTTPRoute` CR per gateway tier (`public`/`private`) per
service, named `<cloudServiceName>-chain-<tier>-routes`, built by fetching the current cluster object, replacing
rules for paths this call touches, and preserving every other rule untouched.

## Goals

- Micro-domain chains get working ingress routing without any `micro-engine` changes: `runtime-catalog` builds
  the `HTTPRoute` CR(s) as part of the same CR bundle it already produces for a domain.
- The same domain's `HTTPRoute` CRs stay consistent across incremental deploys (`DeployMode.APPEND`, adding a
  chain to an already-running domain) and full rebuilds (`DeployMode.REWRITE`), without one deploy call
  clobbering another chain's routes — the same correctness property `ChainRouteRegistry`'s multi-registration
  model already guarantees on the classic-domain path.
- Deleting a domain (`CustomResourceService.delete`) or removing one chain from a running domain
  (`CustomResourceService.deleteChainSnapshot`) cleans up that chain's own routes without disturbing routes
  belonging to other chains in the same domain.
- Reuse the naming convention (`<domain>-chain-<tier>-routes`), gateway names (`public-gateway`/
  `private-gateway`), and route-shape (`PathPrefix` match + `URLRewrite` filter + weighted `backendRefs`)
  `engine`'s Istio path already established, so both domain types produce structurally identical CRs.

## Design

### 1. Two naming strategies, not one

`NamingStrategy<T>.getName(T context)` takes a single context and returns one name — it has no parameter for
"which tier," so a single strategy can't branch between the public and private CR names for the same
`ResourceBuildContext<List<Snapshot>>`. Two small classes, each mirroring `ServiceNamingStrategy`'s existing
shape, fit the established per-resource-name-shape convention in `cr.naming.strategies` better than one class
with an internal branch:

```java
package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

@Component("httpRoutePublicNamingStrategy")
public class HttpRoutePublicNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final K8sNameValidator nameValidator;
    private final String suffix;

    @Autowired
    public HttpRoutePublicNamingStrategy(
            K8sNameVerifier nameVerifier,
            K8sNameValidator nameValidator,
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,
            @Value("${qip.cr.naming.http-route.public-suffix:-chain-public-routes}") String suffix
    ) {
        super(nameVerifier);
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.nameValidator = nameValidator;
        this.suffix = suffix;
    }

    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        return nameValidator.validate(integrationResourceNamingStrategy.getName(context) + suffix);
    }
}
```

`HttpRoutePrivateNamingStrategy` is identical except for its `@Component` qualifier name and its suffix
property/default (`qip.cr.naming.http-route.private-suffix` / `-chain-private-routes`). Both delegate to
`integrationResourceNamingStrategy` for the domain's base name, exactly like `ServiceNamingStrategy` does —
this guarantees the domain name segment matches the Service/Integration CRs the HTTPRoute's `backendRefs`
point at.

### 2. `HttpRouteResourceBuilder`: a common (domain-wide) builder, not a per-chain one

Routes are shared per domain and per gateway tier — not per chain — the same way the domain's `Service` CR is
shared, not duplicated per chain. `HttpRouteResourceBuilder` is therefore a `ResourceBuilder<List<Snapshot>>`
(a "common" builder, alongside `ServiceResourceBuilder`), not a `ResourceBuilder<Snapshot>` (a "chain" builder,
alongside `SourceConfigMapBuilder`).

```java
package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

@Slf4j
@Component
public class HttpRouteResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String CAMEL_ROUTES_PREFIX = "/routes";
    private static final String PUBLIC_GATEWAY_NAME = "public-gateway";
    private static final String PRIVATE_GATEWAY_NAME = "private-gateway";
    private static final int BACKEND_PORT = 8080;

    private final YAMLMapper yamlMapper;
    private final ObjectMapper objectMapper;
    private final RoutesGetterService routesGetterService;
    private final DeploymentRouteMapper deploymentRouteMapper;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Value("${qip.chains.external-routes.base-path:/qip-routes}")
    String baseRoutePrefix;

    @Value("${qip.cr.labels.domain}") String domainLabel;
    @Value("${qip.cr.labels.bg-version}") String bgVersionLabel;
    @Value("${spring.application.deployment_version}") String bgVersion;

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        // true iff this build's own snapshots contain at least one route of either tier;
        // the two tiers are still built independently inside build() (see below) so a
        // build with only public routes still emits nothing for the private CR.
        return !collectRoutes(context).isEmpty();
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        List<DeploymentRouteUpdate> routes = collectRoutes(context);
        StringBuilder out = new StringBuilder();
        appendTier(out, context, routes, RouteType::isExternalTriggerRoute,
                httpRoutePublicNamingStrategy, PUBLIC_GATEWAY_NAME, "publicHttpRoute");
        appendTier(out, context, routes, RouteType::isPrivateTriggerRoute,
                httpRoutePrivateNamingStrategy, PRIVATE_GATEWAY_NAME, "privateHttpRoute");
        return out.toString();
    }

    private void appendTier(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            List<DeploymentRouteUpdate> allRoutes,
            Predicate<RouteType> tierPredicate,
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy,
            String gatewayName,
            String cacheKey
    ) throws Exception {
        List<DeploymentRouteUpdate> tierRoutes = allRoutes.stream()
                .filter(r -> tierPredicate.test(r.getType()))
                .toList();
        if (tierRoutes.isEmpty()) {
            return; // nothing of this tier in this build; leave any existing cluster CR untouched
        }

        String name = namingStrategy.getName(context);
        List<ObjectNode> preservedRules = preservedRulesFromCache(context, cacheKey, tierRoutes);
        List<ObjectNode> newRules = tierRoutes.stream().map(this::buildRule).toList();

        ObjectNode httpRoute = yamlMapper.createObjectNode();
        httpRoute.put("apiVersion", GATEWAY_API_GROUP + "/" + GATEWAY_API_VERSION);
        httpRoute.put("kind", "HTTPRoute");

        ObjectNode metadata = httpRoute.withObjectProperty("metadata");
        metadata.put("name", name);
        ObjectNode labels = metadata.withObject("labels");
        labels.put(domainLabel, k8sNameValidator.validate(context.getBuildInfo().getOptions().getName()));
        labels.put(bgVersionLabel, bgVersion);

        ObjectNode spec = httpRoute.withObjectProperty("spec");
        spec.withArray("parentRefs").addObject()
                .put("group", GATEWAY_API_GROUP).put("kind", "Gateway").put("name", gatewayName);
        var rules = spec.withArray("rules");
        preservedRules.forEach(rules::add);
        newRules.forEach(rules::add);

        out.append(yamlMapper.writeValueAsString(httpRoute)).append("---\n");
    }

    // ... buildRule(route) builds one rules[] entry: PathPrefix match on
    // baseRoutePrefix + route.getPath(), URLRewrite filter to CAMEL_ROUTES_PREFIX + route.getPath(),
    // one backendRef (serviceNamingStrategy name, port 8080, weight 1), optional timeouts —
    // identical shape to IstioRoutesRegistrationService.buildRule.

    // ... preservedRulesFromCache(context, cacheKey, tierRoutes) reads context.getBuildCache().get(cacheKey)
    // (populated by CustomResourceBuildContextFactory in APPEND mode, see §4), filters out any rule whose
    // match path is in tierRoutes' touched-path set, and returns the rest as ObjectNodes to re-emit verbatim.
}
```

Both tiers are handled independently within one `build()` call (one builder instance, not two) since they
share the route-collection and rule-building logic; only the naming strategy, gateway name, and cache key
differ per tier.

### 3. Merge semantics: preserve-by-path, same rule as `ChainRouteRegistry`

`appendTier` only ever **adds or replaces** rules whose path belongs to `tierRoutes` (this build's own
snapshots); every other path already on the cluster's CR is preserved verbatim. This is deploy-time REWRITE vs
APPEND, expressed the same way it already is for `IntegrationsConfigurationConfigMapBuilder`'s config merge:

- **`DeployMode.REWRITE`** (the plain `/v1/cr` build endpoint, and `/deploy` with `mode=REWRITE`):
  `CustomResourceBuildContextFactory` never populates the `publicHttpRoute`/`privateHttpRoute` cache keys, so
  `preservedRulesFromCache` returns an empty list — the CR ends up containing only this build's own routes.
  This matches every other REWRITE-mode builder: the call defines the complete desired state for exactly the
  given snapshots.
- **`DeployMode.APPEND`** (`/deploy` with `mode=APPEND`, adding chains to an already-running domain): the cache
  holds the existing cluster HTTPRoute's rules (see §4), so paths belonging to chains not in this build are
  carried forward untouched, and paths belonging to chains that *are* in this build are replaced with their
  current definition — the same "is this path still claimed by someone else" rule
  `ChainRouteRegistry.getUnsharedRoutes` uses on the classic-domain path, just phrased as "preserve unless
  touched" instead of "return unless claimed."

### 4. `CustomResourceBuildContextFactory`: fetch existing HTTPRoutes for APPEND builds

`addAppendConfigurationToContext` already fetches the domain's existing `Integration`/`IntegrationsConfiguration`
via `customResourceService.getMainIntegrationResources(name)` and stashes derived state into
`context.getBuildCache()` (see `putIntegrationsConfigurationToBuildCache`). It gains one more step: if
`resources.publicHttpRoute()` / `resources.privateHttpRoute()` (see §5) is present, extract its `spec.rules`
(via `objectMapper.convertValue(customObject.getSpec(), ...)` into a `List<ObjectNode>`, or simply read the
raw `Map<String, Object>` and re-wrap) and put it into the build cache under the same `"publicHttpRoute"` /
`"privateHttpRoute"` keys `HttpRouteResourceBuilder` reads.

### 5. `CustomResourceService`: fetch and delete HTTPRoutes alongside the domain's other resources

`IntegrationResources` gains two fields, fetched unconditionally (like `service` is today, not gated behind
`includeAdditionalResources` like `secret`/`customResources` — both the append-merge path and the two lifecycle
methods below need them regardless of which `getIntegrationResources` overload is called):

```java
public record IntegrationResources(
        CamelKIntegration integration,
        V1ServiceMonitor serviceMonitor,
        V1Service service,
        V1ConfigMap integrationsConfiguration,
        Collection<V1ConfigMap> integrationSources,
        V1Secret secret,
        Collection<KubeCustomObject> customResources,
        KubeCustomObject publicHttpRoute,
        KubeCustomObject privateHttpRoute
) { ... }
```

Fetched via a new `KubeOperator.getCustomObject(String group, String version, String plural, String name)` →
`Optional<KubeCustomObject>`, tolerant of a 404 (mirrors `engine`'s own `KubeOperator.getCustomObject`), using
the deterministic names from `httpRoutePublicNamingStrategy`/`httpRoutePrivateNamingStrategy` — a direct
name-based GET, not a label-based list, since the name is already known and deterministic (unlike
`GenericCustomResources`' label-based lookups, which exist for CRs — `FacadeService`/`Mesh`/`DBaaS` — that
this feature has no relationship to).

**`delete(String name)`** (full domain teardown): unconditionally deletes both tier CRs (tolerant of "not
found"), the same way it already unconditionally deletes `service`/`integrationsConfiguration`/etc.

**`deleteChainSnapshot(String name, String snapshotId)`** (removing one chain from a still-running
multi-chain domain): fetches that snapshot's own routes via `routesGetterService.getRoutes(bySnapshotId)`,
computes its touched paths, and for each tier CR that exists: removes any rule whose path is in that set, then
either `createOrUpdateResource`s the reduced CR (other chains' paths remain) or deletes it if no rules remain.

### 6. What happens when a build/merge leaves a tier with zero rules

**Nothing** — an emptied-out `HTTPRoute` CR is left in place rather than deleted or rewritten empty. It's
inert (nothing routes there anymore) and gets cleaned up in exactly two ways: the whole domain is deleted
(`delete(name)`, §5, unconditional), or a later deploy adds a chain back with a route of that tier (a normal
`build()` call for that tier, populated with real rules again). Both `HttpRouteResourceBuilder.enabled()` and
`appendTier` skip a tier entirely when this build's own snapshots contain no route of that type — no
K8s interaction happens for that tier in that deploy call at all, so there's no risk of accidentally emitting
or applying an empty-rules CR.

## Non-goals

- No changes to `micro-engine` — this is the whole point of doing this in `runtime-catalog` instead.
- No changes to `engine`'s classic-domain path (`IstioRoutesRegistrationService`, `ChainRouteRegistry`) —
  this feature is entirely additive on the micro-domain side.
- No optimistic-concurrency/retry handling for two concurrent `/deploy` calls racing on the same domain's
  HTTPRoute. `CustomResourceService.deploy` already has no such protection for its other resource types
  (`Integration`, `ConfigMap`, ...); this feature doesn't add a new correctness requirement beyond what
  already exists for the rest of the CR bundle.
- No `VirtualService`, `Ingress`, `GRPCRoute`, or `TCPRoute` generation — `HTTPRoute` only, matching engine's
  Istio path.
- No egress-gateway route generation — `RoutesGetterService.getRoutes` already includes sender/service routes
  (`EXTERNAL_SENDER`/`EXTERNAL_SERVICE`) when `qip.control-plane.chain-routes-registration.egress-gateway` is
  enabled, but neither tier predicate (`isExternalTriggerRoute`/`isPrivateTriggerRoute`) matches those types,
  so they're naturally excluded from both CRs — consistent with `engine`'s own
  `IstioRoutesRegistrationService.postEgressGatewayRoutes` throwing `UnsupportedOperationException` today.

## Testing

- `HttpRoutePublicNamingStrategyTest` / `HttpRoutePrivateNamingStrategyTest`: name shape
  (`<domain>-chain-public-routes` / `-chain-private-routes`), suffix override via property.
- `HttpRouteResourceBuilderTest`: REWRITE-mode fresh build (only given snapshots' routes appear); APPEND-mode
  merge (a path from a cached "other chain" survives untouched, a path this build's own snapshot previously
  registered gets replaced rather than duplicated); tier skipped entirely when this build has no route of that
  type (`enabled()` false, no cache read, nothing emitted); a route with `EXTERNAL_PRIVATE_TRIGGER` type
  appears in both the public and private CR (mirrors `RouteType.isExternalTriggerRoute` /
  `isPrivateTriggerRoute` both matching it, same as engine's tier split).
- `CustomResourceServiceTest` additions: `delete(name)` removes both tier CRs when present, no-ops cleanly
  when absent; `deleteChainSnapshot` strips only the target snapshot's paths, leaves other chains' rules
  intact, deletes the tier CR entirely when it empties out.
- `KubeOperatorTest` addition: `getCustomObject` returns the object when found, `Optional.empty()` on 404,
  propagates `KubeApiException` on any other failure.
