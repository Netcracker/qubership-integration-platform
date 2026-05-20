package org.qubership.integration.platform.engine.service;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.RouteType;
import org.qubership.integration.platform.engine.util.SimpleHttpUriUtils;

import java.net.MalformedURLException;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.nonNull;

@ApplicationScoped
@IfBuildProperty(name = "qip.control-plane.enabled", stringValue = "true")
public class RouteRegistrationService {
    private final VariablesService variablesService;
    private final ControlPlaneService controlPlaneService;
    private final ApplicationConfiguration applicationConfiguration;

    public RouteRegistrationService(
            VariablesService variablesService,
            ControlPlaneService controlPlaneService,
            ApplicationConfiguration applicationConfiguration
    ) {
        this.variablesService = variablesService;
        this.controlPlaneService = controlPlaneService;
        this.applicationConfiguration = applicationConfiguration;
    }

    public void registerRoutes(Collection<RouteRegistrationInfo> routes) {
        routes = resolveVariablesInRoutes(routes);

        // external triggers routes
        List<RouteRegistrationInfo> gatewayTriggersRoutes = routes.stream()
                .filter(route -> RouteType.triggerRouteWithGateway(route.getType()))
                .peek(externalRoute -> externalRoute
                        .setPath("/" + StringUtils.strip(externalRoute.getPath(), "/")))
                .toList();

        controlPlaneService.postPublicEngineRoutes(
                gatewayTriggersRoutes.stream()
                        .filter(route -> RouteType.isPublicTriggerRoute(route.getType())).toList(),
                applicationConfiguration.getCloudServiceName());
        controlPlaneService.postPrivateEngineRoutes(
                gatewayTriggersRoutes.stream()
                        .filter(route -> RouteType.isPrivateTriggerRoute(route.getType())).toList(),
                applicationConfiguration.getCloudServiceName());

        // cleanup triggers routes if necessary (for internal triggers)
        controlPlaneService.removeEngineRoutesByPathsAndEndpoint(
                routes.stream()
                        .filter(route -> RouteType.triggerRouteCleanupNeeded(route.getType()))
                        .map(route -> Pair.of(route.getPath(), route.getType()))
                        .toList(),
                applicationConfiguration.getCloudServiceName());

        // Register http based senders and service call paths '/{senderType}/{elementId}', '/system/{elementId}'
        routes.stream()
                .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                        || route.getType() == RouteType.EXTERNAL_SERVICE)
                .forEach(route -> controlPlaneService.postEgressGatewayRoutes(formatServiceRoutes(route)));
    }

    private Collection<RouteRegistrationInfo> resolveVariablesInRoutes(Collection<RouteRegistrationInfo> routes) {
        return routes.stream()
                .filter(route -> nonNull(route.getVariableName())
                        && (RouteType.EXTERNAL_SENDER == route.getType()
                        || RouteType.EXTERNAL_SERVICE == route.getType()))
                .filter(route -> variablesService.hasVariableReferences(route.getPath()))
                .map(route -> route.toBuilder().path(variablesService.injectVariables(route.getPath())).build())
                .toList();
    }

    public static @NotNull RouteRegistrationInfo formatServiceRoutes(RouteRegistrationInfo route) {
        RouteRegistrationInfo routeUpdate = route;

        // add hash to route
        if (nonNull(routeUpdate.getVariableName())
                && RouteType.EXTERNAL_SERVICE.equals(routeUpdate.getType())) {
            routeUpdate = routeUpdate.toBuilder().build();
            // Formatting URI (add protocol if needed)
            try {
                routeUpdate.setPath(SimpleHttpUriUtils.formatUri(routeUpdate.getPath()));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }

            // Adding hash to gateway prefix
            String pathHash = getCPRouteHash(routeUpdate);
            if (!StringUtils.isBlank(pathHash)) {
                routeUpdate.setGatewayPrefix(routeUpdate.getGatewayPrefix() + '/' + pathHash);
            }
        }

        return routeUpdate;
    }

    private static String getCPRouteHash(RouteRegistrationInfo route) {
        if (route.getPath() == null) {
            return null;
        }

        // Add all parameters that will be sent to control-plane
        String strToHash = StringUtils.joinWith(",",
                route.getPath(),
                route.getConnectTimeout()
        );

        return DigestUtils.sha1Hex(strToHash);
    }
}
