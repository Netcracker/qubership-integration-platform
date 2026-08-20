package org.qubership.integration.platform.engine.controlplane.impl;

import com.netcracker.cloud.routesregistration.common.gateway.route.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneServiceProperties;
import org.qubership.integration.platform.engine.controlplane.rest.ControlPlaneRestService;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.ActionV1;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.RouteConfigurationResponse;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.RouteMatcherV1;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.RouteV1;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.VirtualHost;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.Destination;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.RouteConfigurationObjectV3;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.RouteV3;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.Rule;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.VirtualService;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.tlsdef.TLSDefinitionObjectV3;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.RouteType;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ControlPlaneServiceImplTest {

    private static final String NAMESPACE = "test-namespace";
    private static final String BLUE_GREEN_VERSION = "v1";
    private static final String GATEWAY_ROUTES_PREFIX = "/qip-routes";
    private static final String CAMEL_ROUTES_PREFIX = "/routes";
    private static final String DEPLOYMENT = "engine-deployment";
    private static final String EGRESS_GATEWAY = "egress-gateway";
    private static final String EGRESS_VIRTUAL_SERVICE = "egress-virtual-service";
    private static final Long CONNECT_TIMEOUT = 120_000L;

    @Mock
    ControlPlaneRestService controlPlaneRestService;

    @Mock
    ApplicationConfiguration applicationConfiguration;

    @Mock
    BlueGreenStateService blueGreenStateService;

    ControlPlaneServiceImpl service;

    @BeforeEach
    void setUp() {
        service = serviceWithInsecureTls(false);
    }

    @Test
    void shouldPostPublicRoutesToPublicGateway() {
        stubDeploymentContext();

        service.postPublicEngineRoutes(
                List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER)), DEPLOYMENT);

        RouteConfigurationObjectV3 configuration = capturePostedConfiguration();
        assertEquals(DEPLOYMENT + "-route", configuration.getMetadata().getName());
        assertEquals(NAMESPACE, configuration.getMetadata().getNamespace());
        assertEquals(List.of(Constants.PUBLIC_GATEWAY_SERVICE), configuration.getSpec().getGateways());

        VirtualService virtualService = singleVirtualService(configuration);
        assertEquals(Constants.PUBLIC_GATEWAY_SERVICE, virtualService.getName());
        assertEquals(List.of("*"), virtualService.getHosts());
        assertEquals(BLUE_GREEN_VERSION, virtualService.getRouteConfiguration().getVersion());
    }

    @Test
    void shouldPostPrivateRoutesToPrivateGateway() {
        stubDeploymentContext();

        service.postPrivateEngineRoutes(
                List.of(triggerRoute("/a", RouteType.PRIVATE_TRIGGER)), DEPLOYMENT);

        RouteConfigurationObjectV3 configuration = capturePostedConfiguration();
        assertEquals(List.of(Constants.PRIVATE_GATEWAY_SERVICE), configuration.getSpec().getGateways());
        assertEquals(Constants.PRIVATE_GATEWAY_SERVICE, singleVirtualService(configuration).getName());
    }

    @Test
    void shouldRewriteGatewayPrefixToCamelPrefixAndTargetTheDeploymentOnPort8080() {
        stubDeploymentContext();

        service.postPublicEngineRoutes(
                List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER)), DEPLOYMENT);

        RouteV3 route = singleRoute(capturePostedConfiguration());
        Destination destination = route.getDestination();
        assertEquals(DEPLOYMENT, destination.getCluster());
        assertEquals("http://" + DEPLOYMENT + ":8080", destination.getEndpoint());

        Rule rule = route.getRules().get(0);
        assertEquals(GATEWAY_ROUTES_PREFIX + "/a", rule.getMatch().getPrefix());
        assertEquals(CAMEL_ROUTES_PREFIX + "/a", rule.getPrefixRewrite());
        assertEquals(CONNECT_TIMEOUT, rule.getTimeout());
        assertEquals(CONNECT_TIMEOUT, rule.getIdleTimeout());
    }

    @Test
    void shouldPostEveryRouteOfTheDeploymentInASingleConfiguration() {
        stubDeploymentContext();

        service.postPublicEngineRoutes(
                List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER),
                        triggerRoute("/b", RouteType.EXTERNAL_TRIGGER)),
                DEPLOYMENT);

        List<RouteV3> routes = singleVirtualService(capturePostedConfiguration())
                .getRouteConfiguration().getRoutes();
        assertEquals(2, routes.size());
        assertEquals(GATEWAY_ROUTES_PREFIX + "/a", routes.get(0).getRules().get(0).getMatch().getPrefix());
        assertEquals(GATEWAY_ROUTES_PREFIX + "/b", routes.get(1).getRules().get(0).getMatch().getPrefix());
    }

    @Test
    void shouldNotCallControlPlaneWhenThereAreNoRoutesToPost() {
        service.postPublicEngineRoutes(List.of(), DEPLOYMENT);
        service.postPrivateEngineRoutes(null, DEPLOYMENT);

        verifyNoInteractions(controlPlaneRestService);
    }

    @Test
    void shouldWrapPostFailureIntoControlPlaneException() {
        stubDeploymentContext();
        RuntimeException restFailure = new RuntimeException("control plane is down");
        when(controlPlaneRestService.postRouteConfiguration(any()))
                .thenThrow(restFailure);

        ControlPlaneException exception = assertThrows(ControlPlaneException.class, () ->
                service.postPublicEngineRoutes(
                        List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER)), DEPLOYMENT));

        assertEquals("Failed to post routes configuration for routes in control plane", exception.getMessage());
        assertSame(restFailure, exception.getCause());
    }

    @Test
    void shouldDeletePublicAndPrivateRoutesOfTheDeployment() {
        when(controlPlaneRestService.getRouteConfiguration()).thenReturn(List.of(
                routeConfiguration(Constants.PUBLIC_GATEWAY_SERVICE,
                        prefixRoute("public-uuid", GATEWAY_ROUTES_PREFIX + "/a", DEPLOYMENT + ":8080")),
                routeConfiguration(Constants.PRIVATE_GATEWAY_SERVICE,
                        prefixRoute("private-uuid", GATEWAY_ROUTES_PREFIX + "/b", DEPLOYMENT + ":8080"))));

        service.removeEngineRoutes(
                List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER),
                        triggerRoute("/b", RouteType.PRIVATE_TRIGGER)),
                DEPLOYMENT);

        verify(controlPlaneRestService).deleteRoute("public-uuid");
        verify(controlPlaneRestService).deleteRoute("private-uuid");
    }

    @Test
    void shouldKeepRoutesOfOtherGatewaysAndOtherDeployments() {
        when(controlPlaneRestService.getRouteConfiguration()).thenReturn(List.of(
                // Right path, but registered for the private gateway -- a public route must not match it.
                routeConfiguration(Constants.PRIVATE_GATEWAY_SERVICE,
                        prefixRoute("other-gateway-uuid", GATEWAY_ROUTES_PREFIX + "/a", DEPLOYMENT + ":8080")),
                routeConfiguration(Constants.PUBLIC_GATEWAY_SERVICE,
                        prefixRoute("other-deployment-uuid", GATEWAY_ROUTES_PREFIX + "/a", "other-deployment:8080"),
                        prefixRoute("other-path-uuid", GATEWAY_ROUTES_PREFIX + "/z", DEPLOYMENT + ":8080"))));

        service.removeEngineRoutes(
                List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER)), DEPLOYMENT);

        verify(controlPlaneRestService, never()).deleteRoute(anyString());
    }

    @Test
    void shouldDeleteRoutesRegisteredWithARegexpWhenNoPrefixMatches() {
        // Routes with path variables are registered as a regexp, so the prefix lookup finds nothing
        // and the service has to fall back to comparing the control-plane regexp form of the path.
        when(controlPlaneRestService.getRouteConfiguration()).thenReturn(List.of(
                routeConfiguration(Constants.PUBLIC_GATEWAY_SERVICE,
                        regexpRoute("regexp-uuid",
                                GATEWAY_ROUTES_PREFIX + "/orders/([^/]+)(/.*)?", DEPLOYMENT + ":8080"))));

        service.removeEngineRoutes(
                List.of(triggerRoute("/orders/{id}", RouteType.EXTERNAL_TRIGGER)), DEPLOYMENT);

        verify(controlPlaneRestService).deleteRoute("regexp-uuid");
    }

    @Test
    void shouldNotCallControlPlaneWhenThereIsNothingToRemove() {
        service.removeEngineRoutes(List.of(), DEPLOYMENT);
        service.removeEngineRoutes(List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER)), "");

        verifyNoInteractions(controlPlaneRestService);
    }

    @Test
    void shouldWrapDeleteFailureIntoControlPlaneException() {
        when(controlPlaneRestService.getRouteConfiguration()).thenReturn(List.of(
                routeConfiguration(Constants.PUBLIC_GATEWAY_SERVICE,
                        prefixRoute("public-uuid", GATEWAY_ROUTES_PREFIX + "/a", DEPLOYMENT + ":8080"))));
        when(controlPlaneRestService.deleteRoute("public-uuid"))
                .thenThrow(new RuntimeException("control plane is down"));

        ControlPlaneException exception = assertThrows(ControlPlaneException.class, () ->
                service.removeEngineRoutes(
                        List.of(triggerRoute("/a", RouteType.EXTERNAL_TRIGGER)), DEPLOYMENT));

        assertEquals("Failed to remove routes from control plane.", exception.getMessage());
    }

    @Test
    void shouldPostEgressRouteWithHostAndPortAsCluster() {
        stubDeploymentContext();

        service.postEgressGatewayRoutes(senderRoute("https://example.com:8443/api/v1", "/system/service-a"));

        RouteConfigurationObjectV3 configuration = capturePostedConfiguration();
        assertEquals("/system/service-a-route", configuration.getMetadata().getName());
        assertEquals(List.of(EGRESS_GATEWAY), configuration.getSpec().getGateways());
        assertEquals(EGRESS_VIRTUAL_SERVICE, singleVirtualService(configuration).getName());

        RouteV3 route = singleRoute(configuration);
        assertEquals("example.com:8443", route.getDestination().getCluster());
        assertEquals("https://example.com:8443", route.getDestination().getEndpoint());

        Rule rule = route.getRules().get(0);
        assertEquals("/system/service-a", rule.getMatch().getPrefix());
        assertEquals("/api/v1", rule.getPrefixRewrite());
        assertEquals(CONNECT_TIMEOUT, rule.getTimeout());
        assertNull(rule.getIdleTimeout());
    }

    @Test
    void shouldRewriteEgressRouteToRootWhenTargetUrlHasNoPath() {
        stubDeploymentContext();

        service.postEgressGatewayRoutes(senderRoute("http://example.com", "/system/service-a"));

        RouteV3 route = singleRoute(capturePostedConfiguration());
        assertEquals("example.com", route.getDestination().getCluster());
        assertEquals("http://example.com", route.getDestination().getEndpoint());
        assertEquals("/", route.getRules().get(0).getPrefixRewrite());
    }

    @Test
    void shouldPostInsecureTlsDefinitionForHttpsEgressRouteWhenInsecureTlsIsEnabled() {
        service = serviceWithInsecureTls(true);
        stubDeploymentContext();

        service.postEgressGatewayRoutes(senderRoute("https://example.com/api/v1", "/system/service-a"));

        ArgumentCaptor<TLSDefinitionObjectV3> tlsCaptor = ArgumentCaptor.forClass(TLSDefinitionObjectV3.class);
        verify(controlPlaneRestService).postTlsConfiguration(tlsCaptor.capture());
        TLSDefinitionObjectV3 tlsDefinition = tlsCaptor.getValue();
        assertEquals("/system/service-a", tlsDefinition.getSpec().getName());
        assertEquals("example.com", tlsDefinition.getSpec().getTls().getSni());
        assertTrue(tlsDefinition.getSpec().getTls().isInsecure());

        assertEquals("/system/service-a",
                singleRoute(capturePostedConfiguration()).getDestination().getTlsConfigName());
    }

    @Test
    void shouldNotPostTlsDefinitionForPlainHttpEgressRoute() {
        service = serviceWithInsecureTls(true);
        stubDeploymentContext();

        service.postEgressGatewayRoutes(senderRoute("http://example.com/api/v1", "/system/service-a"));

        verify(controlPlaneRestService, never()).postTlsConfiguration(any());
        assertNull(singleRoute(capturePostedConfiguration()).getDestination().getTlsConfigName());
    }

    @Test
    void shouldNotPostTlsDefinitionWhenInsecureTlsIsDisabled() {
        stubDeploymentContext();

        service.postEgressGatewayRoutes(senderRoute("https://example.com/api/v1", "/system/service-a"));

        verify(controlPlaneRestService, never()).postTlsConfiguration(any());
        assertNull(singleRoute(capturePostedConfiguration()).getDestination().getTlsConfigName());
    }

    @Test
    void shouldRejectEgressRouteWithoutTargetUrlOrGatewayPrefix() {
        ControlPlaneException withoutTargetUrl = assertThrows(ControlPlaneException.class, () ->
                service.postEgressGatewayRoutes(senderRoute("", "/system/service-a")));
        ControlPlaneException withoutGatewayPrefix = assertThrows(ControlPlaneException.class, () ->
                service.postEgressGatewayRoutes(senderRoute("https://example.com", "")));

        assertEquals("Routes registration parameters must not be null", withoutTargetUrl.getMessage());
        assertEquals("Routes registration parameters must not be null", withoutGatewayPrefix.getMessage());
        verifyNoInteractions(controlPlaneRestService);
    }

    @Test
    void shouldWrapEgressPostFailureIntoControlPlaneException() {
        stubDeploymentContext();
        RuntimeException restFailure = new RuntimeException("control plane is down");
        when(controlPlaneRestService.postRouteConfiguration(any()))
                .thenThrow(restFailure);

        ControlPlaneException exception = assertThrows(ControlPlaneException.class, () ->
                service.postEgressGatewayRoutes(senderRoute("https://example.com", "/system/service-a")));

        assertEquals("Failed to post routes configuration for routes in control plane", exception.getMessage());
        assertSame(restFailure, exception.getCause());
    }

    private ControlPlaneServiceImpl serviceWithInsecureTls(boolean enableInsecureTls) {
        ControlPlaneServiceProperties properties = new TestProperties(
                new TestEgressProperties(EGRESS_GATEWAY, EGRESS_VIRTUAL_SERVICE, enableInsecureTls),
                new TestRoutesProperties(null, GATEWAY_ROUTES_PREFIX));
        return new ControlPlaneServiceImpl(
                controlPlaneRestService,
                applicationConfiguration,
                blueGreenStateService,
                properties,
                CAMEL_ROUTES_PREFIX);
    }

    private void stubDeploymentContext() {
        when(applicationConfiguration.getNamespace()).thenReturn(NAMESPACE);
        when(blueGreenStateService.getBlueGreenVersion()).thenReturn(BLUE_GREEN_VERSION);
    }

    private RouteConfigurationObjectV3 capturePostedConfiguration() {
        ArgumentCaptor<RouteConfigurationObjectV3> captor =
                ArgumentCaptor.forClass(RouteConfigurationObjectV3.class);
        verify(controlPlaneRestService).postRouteConfiguration(captor.capture());
        return captor.getValue();
    }

    private static VirtualService singleVirtualService(RouteConfigurationObjectV3 configuration) {
        List<VirtualService> virtualServices = configuration.getSpec().getVirtualServices();
        assertEquals(1, virtualServices.size());
        return virtualServices.get(0);
    }

    private static RouteV3 singleRoute(RouteConfigurationObjectV3 configuration) {
        List<RouteV3> routes = singleVirtualService(configuration).getRouteConfiguration().getRoutes();
        assertEquals(1, routes.size());
        return routes.get(0);
    }

    private static RouteRegistrationInfo triggerRoute(String path, RouteType type) {
        return RouteRegistrationInfo.builder()
                .path(path)
                .type(type)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    private static RouteRegistrationInfo senderRoute(String targetUrl, String gatewayPrefix) {
        return RouteRegistrationInfo.builder()
                .path(targetUrl)
                .gatewayPrefix(gatewayPrefix)
                .type(RouteType.EXTERNAL_SENDER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    private static RouteConfigurationResponse routeConfiguration(String nodeGroup, RouteV1... routes) {
        return RouteConfigurationResponse.builder()
                .nodeGroup(nodeGroup)
                .virtualHosts(List.of(VirtualHost.builder()
                        .name(nodeGroup)
                        .routes(List.of(routes))
                        .build()))
                .build();
    }

    private static RouteV1 prefixRoute(String uuid, String prefix, String hostRewrite) {
        return RouteV1.builder()
                .uuid(uuid)
                .matcher(RouteMatcherV1.builder().prefix(prefix).build())
                .action(ActionV1.builder().hostRewrite(hostRewrite).build())
                .build();
    }

    private static RouteV1 regexpRoute(String uuid, String regExp, String hostRewrite) {
        return RouteV1.builder()
                .uuid(uuid)
                .matcher(RouteMatcherV1.builder().regExp(regExp).build())
                .action(ActionV1.builder().hostRewrite(hostRewrite).build())
                .build();
    }

    private record TestProperties(
            ControlPlaneServiceProperties.EgressProperties egress,
            ControlPlaneServiceProperties.RoutesProperties routes
    ) implements ControlPlaneServiceProperties {
    }

    private record TestEgressProperties(
            String name,
            String virtualService,
            Boolean enableInsecureTls
    ) implements ControlPlaneServiceProperties.EgressProperties {
    }

    private record TestRoutesProperties(
            ControlPlaneServiceProperties.RegistrationProperties registration,
            String prefix
    ) implements ControlPlaneServiceProperties.RoutesProperties {
    }
}
