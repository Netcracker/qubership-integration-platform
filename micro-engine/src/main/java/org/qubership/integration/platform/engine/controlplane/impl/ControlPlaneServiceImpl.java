package org.qubership.integration.platform.engine.controlplane.impl;

import com.netcracker.cloud.routesregistration.common.gateway.route.Constants;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneServiceProperties;
import org.qubership.integration.platform.engine.controlplane.rest.ControlPlaneRestService;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.RouteConfigurationResponse;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.RouteV1;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.*;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.tlsdef.TLS;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.tlsdef.TLSDefinitionObjectV3;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.tlsdef.TlsSpec;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Slf4j
public class ControlPlaneServiceImpl implements ControlPlaneService {
    private final ControlPlaneRestService controlPlaneRestService;
    private final ApplicationConfiguration applicationConfiguration;
    private final BlueGreenStateService blueGreenStateService;
    private final ControlPlaneServiceProperties properties;
    private final String camelRoutesPrefix;

    public ControlPlaneServiceImpl(
            ControlPlaneRestService controlPlaneRestService,
            ApplicationConfiguration applicationConfiguration,
            BlueGreenStateService blueGreenStateService,
            ControlPlaneServiceProperties properties,
            String camelRoutesPrefix
    ) {
        this.controlPlaneRestService = controlPlaneRestService;
        this.applicationConfiguration = applicationConfiguration;
        this.blueGreenStateService = blueGreenStateService;
        this.properties = properties;
        this.camelRoutesPrefix = camelRoutesPrefix;
    }

    @Override
    public void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName) throws ControlPlaneException {
        postEngineRoutes(deploymentRoutes, deploymentName, Constants.PUBLIC_GATEWAY_SERVICE);
    }

    @Override
    public void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName) throws ControlPlaneException {
        postEngineRoutes(deploymentRoutes, deploymentName, Constants.PRIVATE_GATEWAY_SERVICE);
    }

    @Override
    public void removeEngineRoutesByPathsAndEndpoint(List<Pair<String, RouteType>> paths, String deploymentName) throws ControlPlaneException {
        try {
            if (paths.isEmpty() || deploymentName.isEmpty()) {
                return;
            }

            List<RouteConfigurationResponse> routesList = controlPlaneRestService.getRouteConfiguration();

            List<String> publicPathsToRemove = paths.stream()
                    .filter(pair -> pair.getRight() == RouteType.PRIVATE_TRIGGER
                            || pair.getRight() == RouteType.INTERNAL_TRIGGER)
                    .map(Pair::getKey).toList();
            List<String> privatePathsToRemove = paths.stream()
                    .filter(pair -> pair.getRight() == RouteType.EXTERNAL_TRIGGER
                            || pair.getRight() == RouteType.INTERNAL_TRIGGER)
                    .map(Pair::getKey).toList();

            removeEngineRoutes(publicPathsToRemove, routesList, Constants.PUBLIC_GATEWAY_SERVICE, deploymentName);
            removeEngineRoutes(privatePathsToRemove, routesList, Constants.PRIVATE_GATEWAY_SERVICE, deploymentName);
        } catch (Exception e) {
            log.error("Failed to remove routes from control plane: {}", e.getMessage());
            throw new ControlPlaneException("Failed to remove routes from control plane.", e);
        }
    }

    @Override
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) {
        String targetURL = route.getPath();
        String gatewayPrefix = route.getGatewayPrefix();

        try {
            if (StringUtils.isEmpty(targetURL) || StringUtils.isEmpty(gatewayPrefix)) {
                throw new ControlPlaneException("Routes registration parameters must not be null");
            }

            URI targetURI = URI.create(targetURL);
            boolean isHttps = targetURI.getScheme().equals("https");
            int targetURIPort = targetURI.getPort();
            String clusterName = targetURI.getHost();
            if (targetURIPort > 0) {
                clusterName = clusterName + ":" + targetURIPort;
            }
            String endpoint = targetURI.getScheme() + "://" + clusterName;
            String targetPath = StringUtils.isNotEmpty(targetURI.getPath()) ? targetURI.getPath() : "/";

            // post TLS definition
            String tlsDefinitionName = null;

            if (isHttps && properties.egress().enableInsecureTls()) {
                tlsDefinitionName = gatewayPrefix;
                TLSDefinitionObjectV3 tlsDefinitionObjectV3 = TLSDefinitionObjectV3.builder()
                        .spec(TlsSpec.builder()
                                .name(tlsDefinitionName)
                                .tls(TLS.builder()
                                        .sni(targetURI.getHost())
                                        .insecure(true)
                                        .build())
                                .build())
                        .build();

                controlPlaneRestService.postTlsConfiguration(tlsDefinitionObjectV3);
            }

            // post routes
            Rule.RuleBuilder ruleBuilder = Rule.builder()
                    .match(RouteMatcherV3.builder()
                            .prefix(gatewayPrefix)
                            .build())
                    .prefixRewrite(targetPath)
                    .timeout(route.getConnectTimeout());

            Rule rule = ruleBuilder.build();

            RouteConfigurationObjectV3 configuration = RouteConfigurationObjectV3.builder()
                    .metadata(buildMetadata(gatewayPrefix))
                    .spec(RouteSpec.builder()
                            .gateways(Collections.singletonList(properties.egress().name()))
                            .virtualServices(Collections.singletonList(VirtualService.builder()
                                    .name(properties.egress().virtualService())
                                    .hosts(Collections.singletonList("*"))
                                    .routeConfiguration(RouteConfiguration.builder()
                                            .version(blueGreenStateService.getBlueGreenVersion())
                                            .routes(Collections.singletonList(RouteV3.builder()
                                                    .destination(Destination.builder()
                                                            .cluster(clusterName)
                                                            .endpoint(endpoint)
                                                            .tlsConfigName(tlsDefinitionName)
                                                            .build())
                                                    .rules(Collections.singletonList(rule))
                                                    .build()))
                                            .build())
                                    .build()))
                            .build())
                    .build();

            controlPlaneRestService.postRouteConfiguration(configuration);
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            handlePostError(e);
        }
    }

    /**
     * Add route in control plane
     *
     * @param deploymentRoutes with url in format "/path" and timeout
     * @throws ControlPlaneException if control plane not available (not in dev mode)
     */
    private void postEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint, String gatewayName) throws ControlPlaneException, KubeApiException {
        if (deploymentRoutes == null || deploymentRoutes.isEmpty()) {
            return;
        }
        try {
            List<RouteV3> routes = deploymentRoutes
                    .stream()
                    .map(externalRoute -> getRoute(externalRoute, endpoint))
                    .toList();

            RouteConfigurationObjectV3 configuration = RouteConfigurationObjectV3.builder()
                    .metadata(buildMetadata(endpoint))
                    .spec(RouteSpec.builder()
                            .gateways(Collections.singletonList(gatewayName))
                            .virtualServices(Collections.singletonList(VirtualService.builder()
                                    .name(gatewayName)
                                    .hosts(Collections.singletonList("*"))
                                    .routeConfiguration(RouteConfiguration.builder()
                                            .version(blueGreenStateService.getBlueGreenVersion())
                                            .routes(routes)
                                            .build())
                                    .build()))
                            .build())
                    .build();


            controlPlaneRestService.postRouteConfiguration(configuration);
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            handlePostError(e);
        }
    }

    private void removeEngineRoutes(
            List<String> paths,
            List<RouteConfigurationResponse> routesList,
            @NonNull String gatewayNodegroup,
            String deploymentName
    ) throws ControlPlaneException {
        if (paths.isEmpty()) {
            return;
        }
        Set<String> pathsSet = paths.stream().map(path -> properties.routes().prefix() + path).collect(
                Collectors.toSet());
        String endpoint = deploymentName + ":8080";

        List<String> routesUuids = routesList.stream()
                .filter(route -> gatewayNodegroup.equals(route.getNodeGroup()))
                .flatMap(route -> route.getVirtualHosts().stream().flatMap(vhost -> vhost.getRoutes().stream()))
                .filter(route ->
                        route.getMatcher() != null
                                && route.getAction() != null
                                && pathsSet.contains(route.getMatcher().getPrefix())
                                && endpoint.equals(route.getAction().getHostRewrite()))
                .map(RouteV1::getUuid)
                .collect(Collectors.toList());
        //find route ids with dynamic endpoints
        if (routesUuids.isEmpty()) {
            routesUuids = routesList.stream()
                    .filter(route -> gatewayNodegroup.equals(route.getNodeGroup()))
                    .flatMap(route -> route.getVirtualHosts().stream().flatMap(vhost -> vhost.getRoutes().stream()))
                    .filter(route ->
                            route.getMatcher() != null
                                    && route.getAction() != null
                                    && route.getMatcher().getRegExp() != null
                                    && pathsSet.stream().anyMatch(path -> convertToControlPlaneRegex(path).equals(route.getMatcher().getRegExp()))
                                    && endpoint.equals(route.getAction().getHostRewrite()))
                    .map(RouteV1::getUuid)
                    .toList();
        }

        for (String uuid : routesUuids) {
            try {
                controlPlaneRestService.deleteRoute(uuid);
            } catch (Exception e) {
                throw new ControlPlaneException("Failed to delete routes in control plane", e);
            }
        }
    }

    private RouteV3 getRoute(DeploymentRouteUpdate deploymentRoute, String endpoint) {
        Rule rule = Rule.builder()
                .match(RouteMatcherV3.builder()
                        .prefix(properties.routes().prefix() + deploymentRoute.getPath())
                        .build())
                .prefixRewrite(camelRoutesPrefix + deploymentRoute.getPath())
                .timeout(deploymentRoute.getConnectTimeout())
                .idleTimeout(deploymentRoute.getConnectTimeout())
                .build();

        return RouteV3
                .builder()
                .destination(Destination
                        .builder()
                        .cluster(endpoint)
                        .endpoint("http://" + endpoint + ":8080")
                        .build()
                )
                .rules(Collections.singletonList(rule))
                .build();
    }

    private Metadata buildMetadata(String namePrefix) {
        return Metadata.builder()
                .name(namePrefix + "-route")
                .namespace(applicationConfiguration.getNamespace())
                .build();
    }

    private void handlePostError(Exception exception) throws ControlPlaneException {
        log.error("Failed to post routes configuration for routes in control plane:  {}", exception.getMessage());
        throw new ControlPlaneException("Failed to post routes configuration for routes in control plane", exception);
    }

    private String convertToControlPlaneRegex(String path) {
        Pattern oldRegexFormat = Pattern.compile("\\\\\\/\\*\\*");
        path.replaceAll("/", "\\/");
        Matcher matcherOldRegex = oldRegexFormat.matcher(path);
        path = matcherOldRegex.replaceAll(path);
        path = path.replaceAll("\\{.*?\\}", "([^/]+)");
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        path = path + "(/.*)?";
        return path;
    }
}
