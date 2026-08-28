package org.qubership.integration.platform.camelk.builders.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.ResourceBuildError;
import org.qubership.integration.platform.camelk.model.ResourceBuilder;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.model.routes.RouteType;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.services.EgressServiceRouteFormatter;
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.camelk.util.GatewayDuration;
import org.qubership.integration.platform.camelk.util.paths.EgressTarget;
import org.qubership.integration.platform.camelk.util.paths.GatewayPathMatch;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
@ConditionalOnProperty(name = "qip.istio.enabled", havingValue = "true")
public class EgressRouteResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    public static final String EGRESS_HTTP_ROUTE_CACHE_KEY = "egressHttpRoute";
    private static final String ROUTES_CACHE_KEY = "egressRouteResourceBuilder.routes";
    private static final String SERVICE_ENTRY_CACHE_KEY_PREFIX = "egressServiceEntry:";
    private static final String DESTINATION_RULE_CACHE_KEY_PREFIX = "egressDestinationRule:";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";

    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";

    private final YAMLMapper yamlMapper;
    private final RoutesGetterService routesGetterService;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Value("${qip.gateway.egress.name}")
    String egressGatewayName;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public EgressRouteResourceBuilder(
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,
            RoutesGetterService routesGetterService,

            @Qualifier("httpRouteEgressNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.yamlMapper = yamlMapper;
        this.routesGetterService = routesGetterService;
        this.httpRouteEgressNamingStrategy = httpRouteEgressNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    /** Build-cache key under which the current spec of the named {@code ServiceEntry} is seeded. */
    public static String serviceEntryCacheKey(String hostResourceName) {
        return SERVICE_ENTRY_CACHE_KEY_PREFIX + hostResourceName;
    }

    /** Build-cache key under which the current spec of the named {@code DestinationRule} is seeded. */
    public static String destinationRuleCacheKey(String hostResourceName) {
        return DESTINATION_RULE_CACHE_KEY_PREFIX + hostResourceName;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return !collectRoutes(context).isEmpty();
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        List<Route> routes = collectRoutes(context);

        StringBuilder out = new StringBuilder();
        appendEgressHttpRoute(out, context, routes);
        appendHostResources(out, context, routes);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Route> collectRoutes(ResourceBuildContext<List<Snapshot>> context) {
        Object cached = context.getBuildCache().get(ROUTES_CACHE_KEY);
        if (cached != null) {
            return (List<Route>) cached;
        }
        // RoutesGetterService is per-snapshot, so a multi-snapshot domain build fans out and
        // concatenates before filtering down to the two egress route types.
        List<Route> routes = context.getData().stream()
                .flatMap(snapshot ->
                        routesGetterService.getRoutes(snapshot, context.getServiceCatalog()).stream())
                .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                        || route.getType() == RouteType.EXTERNAL_SERVICE)
                .map(EgressServiceRouteFormatter::formatServiceRoute)
                .toList();
        context.getBuildCache().put(ROUTES_CACHE_KEY, routes);
        return routes;
    }

    private void appendEgressHttpRoute(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            List<Route> routes
    ) {
        if (routes.isEmpty()) {
            return;
        }

        String name = httpRouteEgressNamingStrategy.getName(context);
        List<ObjectNode> preservedRules = preservedRulesFromCache(context, routes);
        List<ObjectNode> newRules = routes.stream().map(this::buildRule).toList();

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
                .put("group", GATEWAY_API_GROUP)
                .put("kind", "Gateway")
                .put("name", egressGatewayName);
        ArrayNode rules = spec.withArray("rules");
        preservedRules.forEach(rules::add);
        newRules.forEach(rules::add);

        appendYamlDocument(out, httpRoute, "egress HTTPRoute CR " + name);
    }

    private ObjectNode buildRule(Route route) {
        EgressTarget target = EgressTarget.parse(route.getPath());
        GatewayPathMatch pathMatch = GatewayPathMatch.forPath(route.getGatewayPrefix());
        ObjectNode rule = yamlMapper.createObjectNode();

        ObjectNode match = rule.withArray("matches").addObject();
        ObjectNode path = match.withObjectProperty("path");
        path.put("type", pathMatch.getType());
        path.put("value", pathMatch.getValue());

        ObjectNode filter = rule.withArray("filters").addObject();
        filter.put("type", "URLRewrite");
        ObjectNode urlRewrite = filter.withObjectProperty("urlRewrite");
        urlRewrite.put("hostname", target.host());
        ObjectNode rewritePath = urlRewrite.withObjectProperty("path");
        rewritePath.put("type", "ReplacePrefixMatch");
        rewritePath.put("replacePrefixMatch", target.path());

        ObjectNode backendRef = rule.withArray("backendRefs").addObject();
        backendRef.put("group", NETWORKING_ISTIO_API_GROUP);
        backendRef.put("kind", "Hostname");
        backendRef.put("name", target.host());
        backendRef.put("port", target.port());
        backendRef.put("weight", 1);

        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            ObjectNode timeouts = rule.withObjectProperty("timeouts");
            timeouts.put("request", GatewayDuration.formatMillis(route.getConnectTimeout()));
        }

        return rule;
    }

    @SuppressWarnings("unchecked")
    private List<ObjectNode> preservedRulesFromCache(
            ResourceBuildContext<List<Snapshot>> context,
            List<Route> routes
    ) {
        Object cached = context.getBuildCache().get(EGRESS_HTTP_ROUTE_CACHE_KEY);
        if (!(cached instanceof Map<?, ?> existingSpec)) {
            return List.of();
        }
        Object rulesRaw = existingSpec.get("rules");
        if (!(rulesRaw instanceof List<?> existingRules)) {
            return List.of();
        }

        Set<GatewayPathMatch> touchedPaths = routes.stream()
                .map(route -> GatewayPathMatch.forPath(route.getGatewayPrefix()))
                .collect(Collectors.toSet());

        List<ObjectNode> preserved = new ArrayList<>();
        for (Object ruleObj : existingRules) {
            ObjectNode ruleNode = yamlMapper.convertValue(ruleObj, ObjectNode.class);
            HttpRouteRuleNormalizer.normalizeIntegralDoubles(ruleNode);
            JsonNode pathNode = ruleNode.path("matches").path(0).path("path");
            String type = pathNode.path("type").asText(null);
            String value = pathNode.path("value").asText(null);
            if (value == null) {
                log.warn("Preserved egress HTTPRoute rule has no recognizable path match "
                        + "(matches[0].path.type/value); keeping it unconditionally rather than risk silently "
                        + "dropping it from the cluster: {}", ruleNode);
                preserved.add(ruleNode);
                continue;
            }
            if (type == null) {
                // Gateway API defaults HTTPPathMatch.type to PathPrefix when omitted.
                type = "PathPrefix";
            }
            if (!touchedPaths.contains(GatewayPathMatch.of(type, value))) {
                preserved.add(ruleNode);
            }
        }
        return preserved;
    }

    private void appendHostResources(
            StringBuilder out, ResourceBuildContext<List<Snapshot>> context, List<Route> routes
    ) {
        // Grouped by hostResourceName(), which is derived from the host alone: a ServiceEntry and a
        // DestinationRule are named after the host, so every port any route targets on that host
        // belongs to one object. All of this build's ports for a host therefore have to be folded
        // into a single document. Emitting one document per port instead writes several documents
        // under the same metadata.name, and applying them in sequence leaves only the last port in
        // the cluster while the egress HTTPRoute still holds a backendRef to the others.
        Map<String, Map<Integer, EgressTarget>> targetsByHostResource = new LinkedHashMap<>();
        for (Route route : routes) {
            EgressTarget target = EgressTarget.parse(route.getPath());
            targetsByHostResource
                    .computeIfAbsent(target.hostResourceName(), hostResourceName -> new LinkedHashMap<>())
                    .putIfAbsent(target.port(), target);
        }
        for (Map.Entry<String, Map<Integer, EgressTarget>> entry : targetsByHostResource.entrySet()) {
            List<EgressTarget> targets = List.copyOf(entry.getValue().values());
            appendServiceEntry(out, context, entry.getKey(), targets);
            List<EgressTarget> httpsTargets = targets.stream().filter(EgressTarget::isHttps).toList();
            if (!httpsTargets.isEmpty()) {
                appendDestinationRule(out, context, entry.getKey(), httpsTargets);
            }
        }
    }

    /**
     * Appends the one {@code ServiceEntry} document for {@code name}, carrying every port
     * {@code targets} reaches on that host plus every port already recorded in the build cache.
     * {@code targets} all resolve to the same {@code hostResourceName()} and so to the same host.
     */
    private void appendServiceEntry(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            String name,
            List<EgressTarget> targets
    ) {
        JsonNode existingSpec = existingHostResourceSpec(context, serviceEntryCacheKey(name));

        List<JsonNode> newPorts = targets.stream().map(target -> {
            ObjectNode newPort = yamlMapper.createObjectNode();
            newPort.put("number", target.port());
            newPort.put("name", target.portName());
            newPort.put("protocol", target.isHttps() ? "HTTPS" : "HTTP");
            return (JsonNode) newPort;
        }).toList();

        ObjectNode serviceEntry = yamlMapper.createObjectNode();
        serviceEntry.put("apiVersion", NETWORKING_ISTIO_API_GROUP + "/" + NETWORKING_ISTIO_API_VERSION);
        serviceEntry.put("kind", "ServiceEntry");
        serviceEntry.withObjectProperty("metadata").put("name", name);

        ObjectNode spec = mutableSpecCopy(existingSpec);
        serviceEntry.set("spec", spec);
        spec.putArray("hosts").add(targets.get(0).host());
        // location and resolution are defaults, not owned fields: an operator who moves the host
        // to MESH_INTERNAL, or off DNS resolution, keeps that choice across deploys.
        if (!spec.hasNonNull("location")) {
            spec.put("location", "MESH_EXTERNAL");
        }
        if (!spec.hasNonNull("resolution")) {
            spec.put("resolution", "DNS");
        }
        ArrayNode ports = spec.putArray("ports");
        mergedEntries(existingSpec.path("ports"), entry -> entry.path("number").asInt(),
                newPorts, yamlMapper.createObjectNode())
                .forEach(ports::add);

        appendYamlDocument(out, serviceEntry, "ServiceEntry " + name);
    }

    /**
     * Appends the one {@code DestinationRule} document for {@code name}, carrying a port-level TLS
     * setting for every HTTPS port {@code targets} reaches on that host plus every setting already
     * recorded in the build cache. Plain HTTP targets are filtered out before this is called.
     */
    private void appendDestinationRule(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            String name,
            List<EgressTarget> targets
    ) {
        JsonNode existingSpec = existingHostResourceSpec(context, destinationRuleCacheKey(name));

        List<JsonNode> newPortLevelSettings = targets.stream().map(target -> {
            ObjectNode newPortLevelSetting = yamlMapper.createObjectNode();
            newPortLevelSetting.putObject("port").put("number", target.port());
            newPortLevelSetting.putObject("tls").put("sni", target.host());
            return (JsonNode) newPortLevelSetting;
        }).toList();

        // tls.mode is a default, not an owned field: an operator who switches a port to MUTUAL
        // keeps that choice across deploys. DISABLE is the one exception; see clearDisabledTlsMode.
        ObjectNode portLevelSettingDefaults = yamlMapper.createObjectNode();
        portLevelSettingDefaults.putObject("tls").put("mode", "SIMPLE");

        ObjectNode destinationRule = yamlMapper.createObjectNode();
        destinationRule.put("apiVersion", NETWORKING_ISTIO_API_GROUP + "/" + NETWORKING_ISTIO_API_VERSION);
        destinationRule.put("kind", "DestinationRule");
        destinationRule.withObjectProperty("metadata").put("name", name);

        ObjectNode spec = mutableSpecCopy(existingSpec);
        destinationRule.set("spec", spec);
        spec.put("host", targets.get(0).host());
        JsonNode existingPortLevelSettings = clearDisabledTlsMode(
                existingSpec.path("trafficPolicy").path("portLevelSettings"),
                targets.stream().map(EgressTarget::port).collect(Collectors.toSet()));
        ArrayNode portLevelSettings = spec.withObjectProperty("trafficPolicy").putArray("portLevelSettings");
        mergedEntries(existingPortLevelSettings,
                entry -> entry.path("port").path("number").asInt(),
                newPortLevelSettings, portLevelSettingDefaults)
                .forEach(portLevelSettings::add);

        appendYamlDocument(out, destinationRule, "DestinationRule " + name);
    }

    /**
     * {@code portLevelSettings} with {@code tls.mode: DISABLE} dropped from the entries for
     * {@code ports}, so the {@code SIMPLE} default lands on them as if it had never been set.
     *
     * <p>{@code tls.mode} is otherwise the operator's to set, and {@code MUTUAL} survives untouched.
     * {@code DISABLE} does not, because it turns off TLS origination while the route still points at
     * an HTTPS port: the gateway would send cleartext to a TLS listener and every request on that
     * route would fail, with nothing in the {@code DestinationRule} to explain why. Entries for
     * other ports are left exactly as they are, {@code DISABLE} included, since this build does not
     * manage them.
     */
    private JsonNode clearDisabledTlsMode(JsonNode portLevelSettings, Set<Integer> ports) {
        if (!portLevelSettings.isArray()) {
            return portLevelSettings;
        }
        ArrayNode cleared = yamlMapper.createArrayNode();
        for (JsonNode entry : portLevelSettings) {
            if (entry.isObject()
                    && ports.contains(entry.path("port").path("number").asInt())
                    && "DISABLE".equals(entry.path("tls").path("mode").asText(null))) {
                ObjectNode withoutMode = ((ObjectNode) entry).deepCopy();
                withoutMode.withObjectProperty("tls").remove("mode");
                cleared.add(withoutMode);
            } else {
                cleared.add(entry);
            }
        }
        return cleared;
    }

    /**
     * A mutable deep copy of the spec seeded into the build cache, or an empty object when nothing
     * was seeded (the object doesn't exist yet, or nothing seeds this cache at all).
     *
     * <p>The caller overwrites only the fields it owns on top of this copy, so every other field
     * survives the deploy: {@code trafficPolicy.tls} (including {@code credentialName}),
     * {@code connectionPool}, {@code outlierDetection}, {@code subsets}, and {@code exportTo} on a
     * {@code DestinationRule}; {@code endpoints}, {@code addresses}, and {@code workloadSelector}
     * on a {@code ServiceEntry}. Building a fresh node instead drops them. {@code MicroDomainService}
     * writes the generated document with a PUT, so a field absent from it is deleted from the
     * cluster: an operator's hand-added client certificate disappears the next time any chain
     * touches that host.
     */
    private ObjectNode mutableSpecCopy(JsonNode existingSpec) {
        return existingSpec.isObject() ? ((ObjectNode) existingSpec).deepCopy() : yamlMapper.createObjectNode();
    }

    /**
     * Reads {@code cacheKey}'s spec out of the build cache (as a generic {@link JsonNode} tree,
     * since {@code ServiceEntry}/{@code DestinationRule} have no typed POJO here), or an empty
     * {@link ObjectNode} if nothing was seeded there -- meaning either the object doesn't exist yet,
     * or (for the "localdev"/no-cluster-access case) nothing seeds this cache at all. Nothing here
     * talks to Kubernetes directly: {@code MicroDomainResourceBuildContextFactory} seeds every existing
     * {@code ServiceEntry}/{@code DestinationRule}'s spec into the build cache up front, the same way
     * it seeds the {@code HTTPRoute} tiers', so this builder stays a pure YAML generator like its
     * siblings. See {@link #serviceEntryCacheKey}/{@link #destinationRuleCacheKey} for the key
     * scheme and merging this chain's own port in, not overwriting another chain's.
     */
    @SuppressWarnings("unchecked")
    private JsonNode existingHostResourceSpec(ResourceBuildContext<List<Snapshot>> context, String cacheKey) {
        Object cached = context.getBuildCache().get(cacheKey);
        if (!(cached instanceof Map<?, ?> existingSpecMap)) {
            return yamlMapper.createObjectNode();
        }
        return yamlMapper.valueToTree((Map<String, Object>) existingSpecMap);
    }

    /**
     * Returns {@code existingList}'s entries, with any entry sharing a key (per {@code keyExtractor})
     * with one of {@code newEntries} folded into that new entry and appended in its place -- i.e.
     * add-or-merge-by-key, never duplicate. {@code newEntries} carries every port of a single host
     * because that host's {@code ServiceEntry}/{@code DestinationRule} is one object; replacing one
     * port at a time would need one document per port under the same name, and only the
     * last-applied one would survive. {@code existingList} may be a Jackson {@code MissingNode}
     * (absent field); that's treated as an empty list, not an error.
     */
    private List<JsonNode> mergedEntries(
            JsonNode existingList, Function<JsonNode, Integer> keyExtractor, List<JsonNode> newEntries,
            JsonNode entryDefaults
    ) {
        Set<Integer> newKeys = newEntries.stream().map(keyExtractor).collect(Collectors.toSet());
        Map<Integer, JsonNode> collidingEntries = new LinkedHashMap<>();
        List<JsonNode> merged = new ArrayList<>();
        if (existingList.isArray()) {
            for (JsonNode entry : existingList) {
                Integer key = keyExtractor.apply(entry);
                if (newKeys.contains(key)) {
                    collidingEntries.put(key, entry);
                } else {
                    merged.add(entry);
                }
            }
        }
        for (JsonNode newEntry : newEntries) {
            merged.add(foldOntoExistingEntry(
                    collidingEntries.get(keyExtractor.apply(newEntry)), newEntry, entryDefaults));
        }
        return merged;
    }

    /**
     * {@code newEntry} folded onto a copy of {@code existingEntry}, with {@code entryDefaults}
     * filling in only what neither of them supplies.
     *
     * <p>Replacing the colliding entry outright instead would discard every field this builder does
     * not write. The entry is where an operator configures the connection to that one port:
     * {@code tls.credentialName}, {@code tls.caCertificates}, {@code tls.subjectAltNames},
     * {@code connectionPool}, and {@code outlierDetection} on a {@code DestinationRule};
     * {@code targetPort} on a {@code ServiceEntry}. Folding keeps them.
     *
     * <p>The three arguments carry the three levels of ownership. {@code newEntry} holds the fields
     * this builder owns outright and rewrites on every deploy. {@code entryDefaults} holds the
     * fields it seeds on creation and then leaves alone, so an operator's later edit sticks.
     * Everything else belongs to whoever wrote it and is copied across untouched.
     */
    private JsonNode foldOntoExistingEntry(JsonNode existingEntry, JsonNode newEntry, JsonNode entryDefaults) {
        ObjectNode folded = existingEntry != null && existingEntry.isObject()
                ? ((ObjectNode) existingEntry).deepCopy()
                : yamlMapper.createObjectNode();
        deepMerge(folded, newEntry);
        applyDefaults(folded, entryDefaults);
        return folded;
    }

    /**
     * {@code base}, mutated in place, with every property of {@code overrides} folded on top.
     * Nested objects recurse, so a key absent from {@code overrides} survives at any depth. Every
     * other value, arrays included, is replaced outright: array elements have no identity to merge
     * on. Values are copied in, never aliased, so {@code overrides} is safe to reuse.
     */
    private static void deepMerge(ObjectNode base, JsonNode overrides) {
        for (Map.Entry<String, JsonNode> property : overrides.properties()) {
            JsonNode baseValue = base.get(property.getKey());
            if (baseValue != null && baseValue.isObject() && property.getValue().isObject()) {
                deepMerge((ObjectNode) baseValue, property.getValue());
            } else {
                base.set(property.getKey(), property.getValue().deepCopy());
            }
        }
    }

    /**
     * {@code base}, mutated in place, gaining every property of {@code defaults} it does not
     * already carry. The mirror image of {@link #deepMerge}: it recurses the same way, but an
     * existing value always wins, so a default is written once and never rewritten.
     */
    private static void applyDefaults(ObjectNode base, JsonNode defaults) {
        if (!defaults.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> property : defaults.properties()) {
            JsonNode baseValue = base.get(property.getKey());
            if (baseValue != null && baseValue.isObject() && property.getValue().isObject()) {
                applyDefaults((ObjectNode) baseValue, property.getValue());
            } else if (baseValue == null || baseValue.isNull()) {
                base.set(property.getKey(), property.getValue().deepCopy());
            }
        }
    }

    private void appendYamlDocument(StringBuilder out, ObjectNode document, String description) {
        try {
            out.append(yamlMapper.writeValueAsString(document));
            if (out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        } catch (Exception e) {
            throw new ResourceBuildError("Failed to build " + description, e);
        }
    }
}
