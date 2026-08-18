package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.CustomResourceBuildError;
import org.qubership.integration.platform.runtime.catalog.cr.EgressServiceRouteFormatter;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.RoutesGetterService;
import org.qubership.integration.platform.runtime.catalog.util.GatewayDuration;
import org.qubership.integration.platform.runtime.catalog.util.paths.EgressTarget;
import org.qubership.integration.platform.runtime.catalog.util.paths.GatewayPathMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.domain.Specification;
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
public class EgressRouteResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    public static final String EGRESS_HTTP_ROUTE_CACHE_KEY = "egressHttpRoute";
    private static final String ROUTES_CACHE_KEY = "egressRouteResourceBuilder.routes";
    private static final String SERVICE_ENTRY_CACHE_KEY_PREFIX = "egressServiceEntry:";
    private static final String DESTINATION_RULE_CACHE_KEY_PREFIX = "egressDestinationRule:";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String EGRESS_GATEWAY_NAME = "egress-gateway";

    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";

    private final YAMLMapper yamlMapper;
    private final RoutesGetterService routesGetterService;
    private final DeploymentRouteMapper deploymentRouteMapper;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

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
            DeploymentRouteMapper deploymentRouteMapper,

            @Qualifier("httpRouteEgressNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.yamlMapper = yamlMapper;
        this.routesGetterService = routesGetterService;
        this.deploymentRouteMapper = deploymentRouteMapper;
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
        List<DeploymentRouteUpdate> routes = collectRoutes(context);

        StringBuilder out = new StringBuilder();
        appendEgressHttpRoute(out, context, routes);
        appendHostResources(out, context, routes);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private List<DeploymentRouteUpdate> collectRoutes(ResourceBuildContext<List<Snapshot>> context) {
        Object cached = context.getBuildCache().get(ROUTES_CACHE_KEY);
        if (cached != null) {
            return (List<DeploymentRouteUpdate>) cached;
        }
        List<String> snapshotIds = context.getData().stream().map(Snapshot::getId).toList();
        List<DeploymentRouteUpdate> updates;
        if (snapshotIds.isEmpty()) {
            updates = List.of();
        } else {
            Specification<ChainElement> spec = (root, query, cb) -> root.get("snapshot").get("id").in(snapshotIds);
            List<DeploymentRoute> routes = routesGetterService.getRoutes(spec);
            updates = deploymentRouteMapper.asUpdates(routes).stream()
                    .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                            || route.getType() == RouteType.EXTERNAL_SERVICE)
                    .map(EgressServiceRouteFormatter::formatServiceRoute)
                    .toList();
        }
        context.getBuildCache().put(ROUTES_CACHE_KEY, updates);
        return updates;
    }

    private void appendEgressHttpRoute(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            List<DeploymentRouteUpdate> routes
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
                .put("name", EGRESS_GATEWAY_NAME);
        ArrayNode rules = spec.withArray("rules");
        preservedRules.forEach(rules::add);
        newRules.forEach(rules::add);

        appendYamlDocument(out, httpRoute, "egress HTTPRoute CR " + name);
    }

    private ObjectNode buildRule(DeploymentRouteUpdate route) {
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
            List<DeploymentRouteUpdate> routes
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
            StringBuilder out, ResourceBuildContext<List<Snapshot>> context, List<DeploymentRouteUpdate> routes
    ) {
        // Keyed on (host, port), not host alone: two routes can legitimately share a host on
        // different ports, and each such pair must reach appendServiceEntry/appendDestinationRule
        // so its port gets merged in -- deduping by host alone would silently drop every port but
        // one, even within this single build.
        Map<String, EgressTarget> targetsByHostAndPort = new LinkedHashMap<>();
        for (DeploymentRouteUpdate route : routes) {
            EgressTarget target = EgressTarget.parse(route.getPath());
            targetsByHostAndPort.putIfAbsent(target.host() + ":" + target.port(), target);
        }
        for (EgressTarget target : targetsByHostAndPort.values()) {
            appendServiceEntry(out, context, target);
            if (target.isHttps()) {
                appendDestinationRule(out, context, target);
            }
        }
    }

    private void appendServiceEntry(StringBuilder out, ResourceBuildContext<List<Snapshot>> context, EgressTarget target) {
        String name = target.hostResourceName();
        JsonNode existingSpec = existingHostResourceSpec(context, serviceEntryCacheKey(name));

        ObjectNode newPort = yamlMapper.createObjectNode();
        newPort.put("number", target.port());
        newPort.put("name", target.isHttps() ? "https" : "http");
        newPort.put("protocol", target.isHttps() ? "HTTPS" : "HTTP");

        ObjectNode serviceEntry = yamlMapper.createObjectNode();
        serviceEntry.put("apiVersion", NETWORKING_ISTIO_API_GROUP + "/" + NETWORKING_ISTIO_API_VERSION);
        serviceEntry.put("kind", "ServiceEntry");
        serviceEntry.withObjectProperty("metadata").put("name", name);

        ObjectNode spec = serviceEntry.withObjectProperty("spec");
        spec.withArray("hosts").add(target.host());
        spec.put("location", "MESH_EXTERNAL");
        spec.put("resolution", "DNS");
        ArrayNode ports = spec.withArray("ports");
        mergedEntries(existingSpec.path("ports"), entry -> entry.path("number").asInt(), newPort)
                .forEach(ports::add);

        appendYamlDocument(out, serviceEntry, "ServiceEntry " + name);
    }

    private void appendDestinationRule(StringBuilder out, ResourceBuildContext<List<Snapshot>> context, EgressTarget target) {
        String name = target.hostResourceName();
        JsonNode existingSpec = existingHostResourceSpec(context, destinationRuleCacheKey(name));

        ObjectNode newPortLevelSetting = yamlMapper.createObjectNode();
        newPortLevelSetting.putObject("port").put("number", target.port());
        ObjectNode tls = newPortLevelSetting.putObject("tls");
        tls.put("mode", "SIMPLE");
        tls.put("sni", target.host());

        ObjectNode destinationRule = yamlMapper.createObjectNode();
        destinationRule.put("apiVersion", NETWORKING_ISTIO_API_GROUP + "/" + NETWORKING_ISTIO_API_VERSION);
        destinationRule.put("kind", "DestinationRule");
        destinationRule.withObjectProperty("metadata").put("name", name);

        ObjectNode spec = destinationRule.withObjectProperty("spec");
        spec.put("host", target.host());
        ArrayNode portLevelSettings = spec.withObjectProperty("trafficPolicy").withArray("portLevelSettings");
        JsonNode existingPortLevelSettings = existingSpec.path("trafficPolicy").path("portLevelSettings");
        mergedEntries(existingPortLevelSettings,
                entry -> entry.path("port").path("number").asInt(), newPortLevelSetting)
                .forEach(portLevelSettings::add);

        appendYamlDocument(out, destinationRule, "DestinationRule " + name);
    }

    /**
     * Reads {@code cacheKey}'s spec out of the build cache (as a generic {@link JsonNode} tree,
     * since {@code ServiceEntry}/{@code DestinationRule} have no typed POJO here), or an empty
     * {@link ObjectNode} if nothing was seeded there -- meaning either the object doesn't exist yet,
     * or (for the "localdev"/no-cluster-access case) nothing seeds this cache at all. Nothing here
     * talks to Kubernetes directly: {@code CustomResourceBuildContextFactory} seeds every existing
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
     * Returns {@code existingList}'s entries with any entry sharing {@code newEntry}'s key (per
     * {@code keyExtractor}) removed, plus {@code newEntry} appended -- i.e. add-or-replace-by-key,
     * never duplicate. {@code existingList} may be a Jackson {@code MissingNode} (absent field);
     * that's treated as an empty list, not an error.
     */
    private List<JsonNode> mergedEntries(
            JsonNode existingList, Function<JsonNode, Integer> keyExtractor, JsonNode newEntry
    ) {
        int newKey = keyExtractor.apply(newEntry);
        List<JsonNode> merged = new ArrayList<>();
        if (existingList.isArray()) {
            for (JsonNode entry : existingList) {
                if (!keyExtractor.apply(entry).equals(newKey)) {
                    merged.add(entry);
                }
            }
        }
        merged.add(newEntry);
        return merged;
    }

    private void appendYamlDocument(StringBuilder out, ObjectNode document, String description) {
        try {
            out.append(yamlMapper.writeValueAsString(document));
            if (out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        } catch (Exception e) {
            throw new CustomResourceBuildError("Failed to build " + description, e);
        }
    }
}
