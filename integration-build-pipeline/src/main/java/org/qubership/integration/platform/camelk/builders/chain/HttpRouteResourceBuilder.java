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
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.camelk.util.GatewayDuration;
import org.qubership.integration.platform.camelk.util.paths.GatewayPathMatch;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class HttpRouteResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    public static final String PUBLIC_HTTP_ROUTE_CACHE_KEY = "publicHttpRoute";
    public static final String PRIVATE_HTTP_ROUTE_CACHE_KEY = "privateHttpRoute";
    private static final String ROUTES_CACHE_KEY = "httpRouteResourceBuilder.routes";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final int BACKEND_PORT = 8080;

    private final YAMLMapper yamlMapper;
    private final RoutesGetterService routesGetterService;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Value("${qip.chains.external-routes.base-path}")
    String baseRoutePrefix;

    @Value("${qip.gateway.public.name}")
    String publicGatewayName;

    @Value("${qip.gateway.private.name}")
    String privateGatewayName;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public HttpRouteResourceBuilder(
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,
            RoutesGetterService routesGetterService,

            @Qualifier("httpRoutePublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy,

            @Qualifier("httpRoutePrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy,

            @Qualifier("serviceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.yamlMapper = yamlMapper;
        this.routesGetterService = routesGetterService;
        this.httpRoutePublicNamingStrategy = httpRoutePublicNamingStrategy;
        this.httpRoutePrivateNamingStrategy = httpRoutePrivateNamingStrategy;
        this.serviceNamingStrategy = serviceNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        List<Route> routes = collectRoutes(context);
        return routes.stream().anyMatch(route ->
                RouteType.isExternalTriggerRoute(route.getType()) || RouteType.isPrivateTriggerRoute(route.getType()));
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        List<Route> routes = collectRoutes(context);
        String backendServiceName = serviceNamingStrategy.getName(context);

        StringBuilder out = new StringBuilder();
        appendTier(out, context, routes, RouteType::isExternalTriggerRoute,
                httpRoutePublicNamingStrategy, publicGatewayName, PUBLIC_HTTP_ROUTE_CACHE_KEY, backendServiceName);
        appendTier(out, context, routes, RouteType::isPrivateTriggerRoute,
                httpRoutePrivateNamingStrategy, privateGatewayName, PRIVATE_HTTP_ROUTE_CACHE_KEY, backendServiceName);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Route> collectRoutes(ResourceBuildContext<List<Snapshot>> context) {
        Object cached = context.getBuildCache().get(ROUTES_CACHE_KEY);
        if (cached != null) {
            return (List<Route>) cached;
        }
        List<Route> routes = collectSnapshotRoutes(context);
        context.getBuildCache().put(ROUTES_CACHE_KEY, routes);
        return routes;
    }

    /**
     * Collects every route of every snapshot in this build, in one pass. {@code RoutesGetterService}
     * is per-snapshot, so a multi-snapshot domain build fans out and concatenates -- the caller
     * filters by tier afterwards.
     */
    private List<Route> collectSnapshotRoutes(ResourceBuildContext<List<Snapshot>> context) {
        return context.getData().stream()
                .flatMap(snapshot ->
                        routesGetterService.getRoutes(snapshot, context.getServiceCatalog()).stream())
                .toList();
    }

    private void appendTier(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            List<Route> allRoutes,
            Predicate<RouteType> tierPredicate,
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy,
            String gatewayName,
            String cacheKey,
            String backendServiceName
    ) {
        List<Route> tierRoutes = allRoutes.stream()
                .filter(route -> tierPredicate.test(route.getType()))
                .toList();
        if (tierRoutes.isEmpty()) {
            return;
        }

        String name = namingStrategy.getName(context);
        List<ObjectNode> preservedRules = preservedRulesFromCache(context, cacheKey, tierRoutes);
        List<ObjectNode> newRules = tierRoutes.stream()
                .map(route -> buildRule(route, backendServiceName))
                .toList();

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
                .put("name", gatewayName);
        ArrayNode rules = spec.withArray("rules");
        preservedRules.forEach(rules::add);
        newRules.forEach(rules::add);

        try {
            out.append(yamlMapper.writeValueAsString(httpRoute));
            if (out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        } catch (Exception e) {
            throw new ResourceBuildError("Failed to build HTTPRoute CR " + name, e);
        }
    }

    private ObjectNode buildRule(Route route, String backendServiceName) {
        GatewayPathMatch pathMatch = GatewayPathMatch.forPath(baseRoutePrefix + route.getPath());
        ObjectNode rule = yamlMapper.createObjectNode();

        ObjectNode match = rule.withArray("matches").addObject();
        ObjectNode path = match.withObjectProperty("path");
        path.put("type", pathMatch.getType());
        path.put("value", pathMatch.getValue());

        ObjectNode backendRef = rule.withArray("backendRefs").addObject();
        backendRef.put("group", "");
        backendRef.put("kind", "Service");
        backendRef.put("name", backendServiceName);
        backendRef.put("port", BACKEND_PORT);
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
            String cacheKey,
            List<Route> tierRoutes
    ) {
        Object cached = context.getBuildCache().get(cacheKey);
        if (!(cached instanceof Map<?, ?> existingSpec)) {
            return List.of();
        }
        Object rulesRaw = existingSpec.get("rules");
        if (!(rulesRaw instanceof List<?> existingRules)) {
            return List.of();
        }

        Set<GatewayPathMatch> touchedPaths = tierRoutes.stream()
                .map(route -> GatewayPathMatch.forPath(baseRoutePrefix + route.getPath()))
                .collect(Collectors.toSet());

        List<ObjectNode> preserved = new ArrayList<>();
        for (Object ruleObj : existingRules) {
            ObjectNode ruleNode = yamlMapper.convertValue(ruleObj, ObjectNode.class);
            HttpRouteRuleNormalizer.normalizeIntegralDoubles(ruleNode);
            JsonNode pathNode = ruleNode.path("matches").path(0).path("path");
            String type = pathNode.path("type").asText(null);
            String value = pathNode.path("value").asText(null);
            if (value == null) {
                log.warn("Preserved HTTPRoute rule under cache key '{}' has no recognizable path match "
                        + "(matches[0].path.type/value); keeping it unconditionally rather than risk silently "
                        + "dropping it from the cluster: {}", cacheKey, ruleNode);
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
}
