package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.model.controlplane.v1.get.RouteConfigurationResponse;
import org.qubership.integration.platform.engine.model.controlplane.v1.get.RouteV1;
import org.qubership.integration.platform.engine.model.controlplane.v3.post.*;
import org.qubership.integration.platform.engine.model.controlplane.v3.post.tlsdef.TLS;
import org.qubership.integration.platform.engine.model.controlplane.v3.post.tlsdef.TLSDefinitionObjectV3;
import org.qubership.integration.platform.engine.model.controlplane.v3.post.tlsdef.TlsSpec;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.engine.configuration.camel.CamelServletConfiguration.CAMEL_ROUTES_PREFIX;

@Slf4j
@Component("controlPlaneService")
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
public class ControlPlaneDefaultService implements ControlPlaneService {

    private static final String CAMEL_ROUTES_REWRITE_PREFIX = CAMEL_ROUTES_PREFIX;
    public static final String PUBLIC_GATEWAY_SERVICE_NODEGROUP = "public-gateway-service";
    public static final String PRIVATE_GATEWAY_SERVICE_NODEGROUP = "private-gateway-service";

    @Value("${cloud.microservice.namespace}")
    private String namespace;

    @Value("${qip.control-plane.host}")
    private String controlPlaneHost;

    @Value("${qip.control-plane.egress.name}")
    private String egressGatewayName;

    @Value("${qip.control-plane.routes.endpoints.v3.public-gateway-name}")
    private String publicGatewayName;

    @Value("${qip.control-plane.routes.endpoints.v3.private-gateway-name}")
    private String privateGatewayName;

    @Value("${qip.control-plane.egress.virtual-service}")
    private String virtualServiceName;

    @Value("${qip.control-plane.egress.enable-insecure-tls}")
    private boolean enableInsecureTls;

    @Value("{qip.chains.external-routes.base-path}")
    private String baseRoutePrefix;
    @Value("${qip.control-plane.routes.endpoints.v1.get-routes}")
    private String getRoutesEndpoint;
    @Value("${qip.control-plane.routes.endpoints.v2.delete-routes-by-uuid}")
    private String deleteRoutesByUUID;

    @Value("${qip.control-plane.routes.endpoints.v3.post-configuration}")
    private String postConfigurationEndpoint;

    private final RestTemplate restTemplateMS;
    private final ObjectMapper jsonMapper;
    private final BlueGreenStateService blueGreenStateService;

    @Autowired
    public ControlPlaneDefaultService(
            RestTemplate restTemplateMS,
            ObjectMapper jsonMapper,
            BlueGreenStateService blueGreenStateService
    ) {
        this.restTemplateMS = restTemplateMS;
        this.jsonMapper = jsonMapper;
        this.blueGreenStateService = blueGreenStateService;
    }

    /**
     * Get all routes in control plane
     *
     * @return routes
     * @throws JsonProcessingException failed to deserialize response
     * @throws ControlPlaneException   if control plane not available
     */
    public List<RouteConfigurationResponse> getRoutesList() throws JsonProcessingException, ControlPlaneException, RestClientException {
        ResponseEntity<String> response = restTemplateMS
                .getForEntity(controlPlaneHost + getRoutesEndpoint, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            return jsonMapper.readValue(response.getBody(), new TypeReference<>() {
            });
        } else {
            log.error("Failed to get routes, control plane response with non 2xx code");
            throw new ControlPlaneException("Request to control plane failed with code: " + response.getStatusCode());
        }
    }

    @Override
    public void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint) throws ControlPlaneException {
        postEngineRoutes(deploymentRoutes, endpoint, publicGatewayName);
    }

    @Override
    public void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint) throws ControlPlaneException {
        postEngineRoutes(deploymentRoutes, endpoint, privateGatewayName);
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
                    .metadata(Metadata.builder().name(endpoint + "-route").namespace(namespace).build())
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


            postConfigurationV3(configuration);
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to post routes configuration for routes in control plane:  {}", e.getMessage());
            throw new ControlPlaneException("Failed to post routes configuration for routes in control plane", e);
        }
    }

    @Override
    public void removeEngineRoutesByPathsAndEndpoint(List<Pair<String, RouteType>> paths, String deploymentName) throws ControlPlaneException {
        try {
            if (paths.isEmpty() || deploymentName.isEmpty()) {
                return;
            }

            List<RouteConfigurationResponse> routesList = getRoutesList();

            List<String> publicPathsToRemove = paths.stream()
                .filter(pair -> pair.getRight() == RouteType.PRIVATE_TRIGGER || pair.getRight() == RouteType.INTERNAL_TRIGGER)
                .map(Pair::getKey).toList();
            List<String> privatePathsToRemove = paths.stream()
                .filter(pair -> pair.getRight() == RouteType.EXTERNAL_TRIGGER || pair.getRight() == RouteType.INTERNAL_TRIGGER)
                .map(Pair::getKey).toList();

            removeEngineRoutesByPathsAndEndpoint(publicPathsToRemove, routesList, PUBLIC_GATEWAY_SERVICE_NODEGROUP, deploymentName);
            removeEngineRoutesByPathsAndEndpoint(privatePathsToRemove, routesList, PRIVATE_GATEWAY_SERVICE_NODEGROUP, deploymentName);
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof HttpServerErrorException hsee && hsee.getMessage() != null
                    && hsee.getMessage().contains("route does not exist")) {
                return; // route already removed, exit
            }
            log.error("Failed to remove routes from control plane: {}", e.getMessage());
            throw new ControlPlaneException("Failed to remove routes from control plane.", e);
        }
    }

    private void removeEngineRoutesByPathsAndEndpoint(
        List<String> paths,
        List<RouteConfigurationResponse> routesList,
        @NonNull String gatewayNodegroup,
        String deploymentName
    ) throws ControlPlaneException {
        if (paths.isEmpty()) {
            return;
        }
        Set<String> pathsSet = paths.stream().map(path -> baseRoutePrefix + path).collect(
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
                    .collect(Collectors.toList());
        }

        for (String uuid : routesUuids) {
            ResponseEntity<String> response = restTemplateMS
                .exchange(controlPlaneHost + deleteRoutesByUUID + "/{id}", HttpMethod.DELETE, null, String.class, uuid);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Failed to remove routes, control plane response with non 2xx code: {}, {}", response.getStatusCode(), response.getBody());

                throw new ControlPlaneException("Failed to remove routes, control plane response with non 200 code", new RuntimeException(response.getBody()));
            }
        }
    }

    /**
     * Post routes to egress gateway via control plane configuration.
     * On gateway some headers of original request will be removed, like 'Origin', 'Authorization' (set in HEADERS_TO_REMOVE)
     * By default prefixRewrite is '/'
     *
     * @param route  route configuration
     */
    @Override
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) throws ControlPlaneException {
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

            if (isHttps && enableInsecureTls) {
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

                postConfigurationV3(tlsDefinitionObjectV3);
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
                .metadata(Metadata.builder().name(gatewayPrefix + "-route").namespace(namespace).build())
                .spec(RouteSpec.builder()
                    .gateways(Collections.singletonList(egressGatewayName))
                    .virtualServices(Collections.singletonList(VirtualService.builder()
                        .name(virtualServiceName)
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

            postConfigurationV3(configuration);
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to post routes configuration for routes in control plane: {}", e.getMessage());
            throw new ControlPlaneException("Failed to post routes configuration for routes in control plane", e);
        }
    }

    private RouteV3 getRoute(DeploymentRouteUpdate deploymentRoute, String endpoint) {
        try {

            Rule.RuleBuilder ruleBuilder = Rule.builder()
                    .match(RouteMatcherV3.builder()
                            .prefix(baseRoutePrefix + deploymentRoute.getPath())
                            .build())
                    .prefixRewrite(CAMEL_ROUTES_REWRITE_PREFIX + deploymentRoute.getPath());

            Rule rule = ruleBuilder
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

        } catch (Exception e) {
            log.error("Failed to post routes configuration for routes in control plane: {}", e.getMessage());
            throw new ControlPlaneException("Failed to post routes configuration for routes in control plane", e);
        }
    }

    /**
     * Create or update route configuration or tls definition V3
     */
    private void postConfigurationV3(Object configuration) throws JsonProcessingException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(jsonMapper.writeValueAsString(configuration), headers);
        ResponseEntity<String> response = restTemplateMS
                .exchange(controlPlaneHost + postConfigurationEndpoint, HttpMethod.POST, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to post configuration for routes, control plane response with non 2xx code: {}, {}", response.getStatusCode(), response.getBody());
            throw new ControlPlaneException("Failed to post configuration for routes, control plane response with non 2xx code");
        }
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
