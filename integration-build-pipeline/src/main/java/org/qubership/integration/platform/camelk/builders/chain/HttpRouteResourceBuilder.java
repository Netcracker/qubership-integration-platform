package org.qubership.integration.platform.camelk.builders.chain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;

@Component
@ConditionalOnProperty(name = "cip.control-plane.mesh-type", havingValue = "Istio")
@ConditionalOnProperty(name = "cip.istio.enabled", havingValue = "true")
public class HttpRouteResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    private static final String ROUTES_CACHE_KEY = "httpRouteResourceBuilder.routes";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final int BACKEND_PORT = 8080;

    private final YAMLMapper yamlMapper;
    private final RoutesGetterService routesGetterService;
    private final NamingStrategy<ResourceBuildContext<Snapshot>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<Snapshot>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Value("${cip.chains.external-routes.base-path}")
    String baseRoutePrefix;

    @Value("${cip.gateway.public.name}")
    String publicGatewayName;

    @Value("${cip.gateway.private.name}")
    String privateGatewayName;

    @Value("${cip.cr.labels.domain}")
    String domainLabel;

    @Value("${cip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public HttpRouteResourceBuilder(
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,
            RoutesGetterService routesGetterService,

            @Qualifier("httpRoutePublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<Snapshot>> httpRoutePublicNamingStrategy,

            @Qualifier("httpRoutePrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<Snapshot>> httpRoutePrivateNamingStrategy,

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
        return collectRoutes(context).values().stream()
                .flatMap(List::stream)
                .anyMatch(route -> RouteType.isExternalTriggerRoute(route.getType())
                        || RouteType.isPrivateTriggerRoute(route.getType()));
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        Map<String, List<Route>> routesBySnapshotId = collectRoutes(context);
        String backendServiceName = serviceNamingStrategy.getName(context);

        StringBuilder out = new StringBuilder();
        for (Snapshot snapshot : context.getData()) {
            ResourceBuildContext<Snapshot> chainContext = context.updateTo(snapshot);
            List<Route> routes = routesBySnapshotId.get(snapshot.getId());
            appendTier(out, chainContext, routes, RouteType::isExternalTriggerRoute,
                    httpRoutePublicNamingStrategy, publicGatewayName, backendServiceName);
            appendTier(out, chainContext, routes, RouteType::isPrivateTriggerRoute,
                    httpRoutePrivateNamingStrategy, privateGatewayName, backendServiceName);
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Route>> collectRoutes(ResourceBuildContext<List<Snapshot>> context) {
        Object cached = context.getBuildCache().get(ROUTES_CACHE_KEY);
        if (cached != null) {
            return (Map<String, List<Route>>) cached;
        }
        Map<String, List<Route>> routes = new LinkedHashMap<>();
        context.getData().forEach(snapshot ->
                routes.put(snapshot.getId(), routesGetterService.getRoutes(snapshot, context.getServiceCatalog())));
        context.getBuildCache().put(ROUTES_CACHE_KEY, routes);
        return routes;
    }

    private void appendTier(
            StringBuilder out,
            ResourceBuildContext<Snapshot> context,
            List<Route> snapshotRoutes,
            Predicate<RouteType> tierPredicate,
            NamingStrategy<ResourceBuildContext<Snapshot>> namingStrategy,
            String gatewayName,
            String backendServiceName
    ) {
        List<Route> tierRoutes = snapshotRoutes.stream()
                .filter(route -> tierPredicate.test(route.getType()))
                .toList();
        if (tierRoutes.isEmpty()) {
            return;
        }

        String name = namingStrategy.getName(context);

        ObjectNode httpRoute = yamlMapper.createObjectNode();
        httpRoute.put("apiVersion", GATEWAY_API_GROUP + "/" + GATEWAY_API_VERSION);
        httpRoute.put("kind", "HTTPRoute");

        ObjectNode metadata = httpRoute.withObjectProperty("metadata");
        metadata.put("name", name);
        ObjectNode labels = metadata.withObject("labels");
        labels.put(domainLabel, k8sNameValidator.validate(context.getBuildInfo().getOptions().getName()));
        labels.put(bgVersionLabel, bgVersion);
        labels.put(SNAPSHOT_ID_LABEL, k8sNameValidator.validate(context.getData().getId()));

        ObjectNode spec = httpRoute.withObjectProperty("spec");
        spec.withArray("parentRefs").addObject()
                .put("group", GATEWAY_API_GROUP)
                .put("kind", "Gateway")
                .put("name", gatewayName);
        ArrayNode rules = spec.withArray("rules");
        tierRoutes.forEach(route -> rules.add(buildRule(route, backendServiceName)));

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
}
