package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HttpRouteResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    public static final String PUBLIC_HTTP_ROUTE_CACHE_KEY = "publicHttpRoute";
    public static final String PRIVATE_HTTP_ROUTE_CACHE_KEY = "privateHttpRoute";
    private static final String ROUTES_CACHE_KEY = "httpRouteResourceBuilder.routes";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String CAMEL_ROUTES_PREFIX = "/routes";
    private static final String PUBLIC_GATEWAY_NAME = "public-gateway";
    private static final String PRIVATE_GATEWAY_NAME = "private-gateway";
    private static final int BACKEND_PORT = 8080;

    private final YAMLMapper yamlMapper;
    private final RoutesGetterService routesGetterService;
    private final DeploymentRouteMapper deploymentRouteMapper;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Value("${qip.chains.external-routes.base-path:/qip-routes}")
    String baseRoutePrefix;

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
            DeploymentRouteMapper deploymentRouteMapper,

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
        this.deploymentRouteMapper = deploymentRouteMapper;
        this.httpRoutePublicNamingStrategy = httpRoutePublicNamingStrategy;
        this.httpRoutePrivateNamingStrategy = httpRoutePrivateNamingStrategy;
        this.serviceNamingStrategy = serviceNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        List<DeploymentRouteUpdate> routes = collectRoutes(context);
        return routes.stream().anyMatch(route ->
                RouteType.isExternalTriggerRoute(route.getType()) || RouteType.isPrivateTriggerRoute(route.getType()));
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        List<DeploymentRouteUpdate> routes = collectRoutes(context);
        String backendServiceName = serviceNamingStrategy.getName(context);

        StringBuilder out = new StringBuilder();
        appendTier(out, context, routes, RouteType::isExternalTriggerRoute,
                httpRoutePublicNamingStrategy, PUBLIC_GATEWAY_NAME, PUBLIC_HTTP_ROUTE_CACHE_KEY, backendServiceName);
        appendTier(out, context, routes, RouteType::isPrivateTriggerRoute,
                httpRoutePrivateNamingStrategy, PRIVATE_GATEWAY_NAME, PRIVATE_HTTP_ROUTE_CACHE_KEY, backendServiceName);
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
            updates = deploymentRouteMapper.asUpdates(routes);
        }
        context.getBuildCache().put(ROUTES_CACHE_KEY, updates);
        return updates;
    }

    private void appendTier(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            List<DeploymentRouteUpdate> allRoutes,
            Predicate<RouteType> tierPredicate,
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy,
            String gatewayName,
            String cacheKey,
            String backendServiceName
    ) {
        List<DeploymentRouteUpdate> tierRoutes = allRoutes.stream()
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
            throw new org.qubership.integration.platform.runtime.catalog.cr.CustomResourceBuildError(
                    "Failed to build HTTPRoute CR " + name, e);
        }
    }

    private ObjectNode buildRule(DeploymentRouteUpdate route, String backendServiceName) {
        String path = baseRoutePrefix + route.getPath();
        ObjectNode rule = yamlMapper.createObjectNode();

        ObjectNode match = rule.withArray("matches").addObject();
        ObjectNode pathMatch = match.withObjectProperty("path");
        pathMatch.put("type", "PathPrefix");
        pathMatch.put("value", path);

        ObjectNode filter = rule.withArray("filters").addObject();
        filter.put("type", "URLRewrite");
        ObjectNode urlRewrite = filter.withObjectProperty("urlRewrite");
        ObjectNode rewritePath = urlRewrite.withObjectProperty("path");
        rewritePath.put("type", "ReplacePrefixMatch");
        rewritePath.put("replacePrefixMatch", CAMEL_ROUTES_PREFIX + route.getPath());

        ObjectNode backendRef = rule.withArray("backendRefs").addObject();
        backendRef.put("group", "");
        backendRef.put("kind", "Service");
        backendRef.put("name", backendServiceName);
        backendRef.put("port", BACKEND_PORT);
        backendRef.put("weight", 1);

        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            ObjectNode timeouts = rule.withObjectProperty("timeouts");
            timeouts.put("request", route.getConnectTimeout() + "ms");
        }

        return rule;
    }

    @SuppressWarnings("unchecked")
    private List<ObjectNode> preservedRulesFromCache(
            ResourceBuildContext<List<Snapshot>> context,
            String cacheKey,
            List<DeploymentRouteUpdate> tierRoutes
    ) {
        Object cached = context.getBuildCache().get(cacheKey);
        if (!(cached instanceof Map<?, ?> existingSpec)) {
            return List.of();
        }
        Object rulesRaw = existingSpec.get("rules");
        if (!(rulesRaw instanceof List<?> existingRules)) {
            return List.of();
        }

        Set<String> touchedPaths = tierRoutes.stream()
                .map(route -> baseRoutePrefix + route.getPath())
                .collect(Collectors.toSet());

        List<ObjectNode> preserved = new ArrayList<>();
        for (Object ruleObj : existingRules) {
            ObjectNode ruleNode = yamlMapper.convertValue(ruleObj, ObjectNode.class);
            normalizeIntegralDoubles(ruleNode);
            String path = ruleNode.path("matches").path(0).path("path").path("value").asText(null);
            if (path == null) {
                log.warn("Preserved HTTPRoute rule under cache key '{}' has no recognizable path match "
                        + "(matches[0].path.value); keeping it unconditionally rather than risk silently "
                        + "dropping it from the cluster: {}", cacheKey, ruleNode);
                preserved.add(ruleNode);
                continue;
            }
            if (!touchedPaths.contains(path)) {
                preserved.add(ruleNode);
            }
        }
        return preserved;
    }

    /**
     * Recursively rewrites {@link DoubleNode} values that hold whole numbers (as produced by
     * {@code io.kubernetes.client.openapi.JSON}'s Gson instance, which decodes every JSON number
     * as {@code Double} by default) into integral nodes. Without this, a preserved rule's
     * {@code port}/{@code weight} would be re-emitted as e.g. {@code 8080.0}, which the Gateway
     * API's int32-typed schema rejects at apply time.
     */
    private void normalizeIntegralDoubles(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode value = objectNode.get(fieldName);
                if (value instanceof DoubleNode doubleNode) {
                    Long integral = toIntegralIfWhole(doubleNode);
                    if (integral != null) {
                        objectNode.put(fieldName, integral);
                    }
                } else {
                    normalizeIntegralDoubles(value);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode value = arrayNode.get(i);
                if (value instanceof DoubleNode doubleNode) {
                    Long integral = toIntegralIfWhole(doubleNode);
                    if (integral != null) {
                        arrayNode.set(i, LongNode.valueOf(integral));
                    }
                } else {
                    normalizeIntegralDoubles(value);
                }
            }
        }
    }

    private Long toIntegralIfWhole(DoubleNode doubleNode) {
        double value = doubleNode.asDouble();
        return (Double.isFinite(value) && value == Math.rint(value)) ? (long) value : null;
    }
}
