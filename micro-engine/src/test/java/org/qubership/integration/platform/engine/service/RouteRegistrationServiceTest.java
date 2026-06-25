package org.qubership.integration.platform.engine.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.RouteType;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RouteRegistrationServiceTest {

    private static final String ENDPOINT = "engine-service";

    private RouteRegistrationService routeRegistrationService;

    @Mock
    private VariablesService variablesService;
    @Mock
    private ControlPlaneService controlPlaneService;
    @Mock
    private ApplicationConfiguration applicationConfiguration;

    @BeforeEach
    void setUp() {
        routeRegistrationService = new RouteRegistrationService(
                variablesService,
                controlPlaneService,
                applicationConfiguration);
    }

    @Test
    void shouldRegisterTriggerRoutesAndCleanupInternalRoutes() {
        RouteRegistrationInfo publicRoute = route("///public///", RouteType.EXTERNAL_TRIGGER);
        RouteRegistrationInfo privateRoute = route("private", RouteType.PRIVATE_TRIGGER);
        RouteRegistrationInfo publicPrivateRoute = route("/public-private/", RouteType.EXTERNAL_PRIVATE_TRIGGER);
        RouteRegistrationInfo internalRoute = route("/internal", RouteType.INTERNAL_TRIGGER);
        when(applicationConfiguration.getCloudServiceName()).thenReturn(ENDPOINT);

        routeRegistrationService.registerRoutes(List.of(
                publicRoute,
                privateRoute,
                publicPrivateRoute,
                internalRoute));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<RouteRegistrationInfo>> publicRoutesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<RouteRegistrationInfo>> privateRoutesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Pair<String, RouteType>>> cleanupCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);

        verify(controlPlaneService).postPublicEngineRoutes(publicRoutesCaptor.capture(), eq(ENDPOINT));
        verify(controlPlaneService).postPrivateEngineRoutes(privateRoutesCaptor.capture(), eq(ENDPOINT));
        verify(controlPlaneService).removeEngineRoutesByPathsAndEndpoint(cleanupCaptor.capture(), eq(ENDPOINT));
        verify(controlPlaneService, never()).postEgressGatewayRoutes(any());

        assertEquals(List.of(publicRoute, publicPrivateRoute), publicRoutesCaptor.getValue());
        assertEquals(List.of("/public", "/public-private"), publicRoutesCaptor.getValue().stream()
                .map(RouteRegistrationInfo::getPath)
                .toList());
        assertEquals(List.of(privateRoute, publicPrivateRoute), privateRoutesCaptor.getValue());
        assertEquals(List.of("/private", "/public-private"), privateRoutesCaptor.getValue().stream()
                .map(RouteRegistrationInfo::getPath)
                .toList());
        assertEquals(List.of(
                Pair.of("/public", RouteType.EXTERNAL_TRIGGER),
                Pair.of("/private", RouteType.PRIVATE_TRIGGER),
                Pair.of("/internal", RouteType.INTERNAL_TRIGGER)
        ), cleanupCaptor.getValue());
    }

    @Test
    void shouldResolveVariablesAndRegisterEgressGatewayRoutes() {
        RouteRegistrationInfo senderRoute = route("http://#{senderHost}/orders", RouteType.EXTERNAL_SENDER)
                .toBuilder()
                .variableName("senderGatewayPrefix")
                .gatewayPrefix("/orders")
                .build();
        RouteRegistrationInfo serviceRoute = route("http://inventory/api", RouteType.EXTERNAL_SERVICE)
                .toBuilder()
                .gatewayPrefix("/inventory")
                .build();
        when(applicationConfiguration.getCloudServiceName()).thenReturn(ENDPOINT);
        when(variablesService.hasVariableReferences("http://#{senderHost}/orders")).thenReturn(true);
        when(variablesService.injectVariables("http://#{senderHost}/orders"))
                .thenReturn("http://sender-service/orders");

        routeRegistrationService.registerRoutes(List.of(senderRoute, serviceRoute));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<RouteRegistrationInfo> egressRouteCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(RouteRegistrationInfo.class);
        verify(controlPlaneService, times(2)).postEgressGatewayRoutes(egressRouteCaptor.capture());
        verify(controlPlaneService).postPublicEngineRoutes(List.of(), ENDPOINT);
        verify(controlPlaneService).postPrivateEngineRoutes(List.of(), ENDPOINT);
        verify(controlPlaneService).removeEngineRoutesByPathsAndEndpoint(List.of(), ENDPOINT);

        List<RouteRegistrationInfo> egressRoutes = egressRouteCaptor.getAllValues();
        assertEquals("http://sender-service/orders", egressRoutes.get(0).getPath());
        assertEquals(RouteType.EXTERNAL_SENDER, egressRoutes.get(0).getType());
        assertEquals(serviceRoute, egressRoutes.get(1));
    }

    @Test
    void shouldFormatExternalServiceRouteWithHashInGatewayPrefix() {
        RouteRegistrationInfo route = route("inventory/api", RouteType.EXTERNAL_SERVICE)
                .toBuilder()
                .variableName("inventoryGatewayPrefix")
                .gatewayPrefix("/inventory")
                .connectTimeout(5000L)
                .build();

        RouteRegistrationInfo result = RouteRegistrationService.formatServiceRoutes(route);

        String expectedPath = "https://inventory/api";
        String expectedHash = DigestUtils.sha1Hex(expectedPath + ",5000");
        assertNotSame(route, result);
        assertEquals(expectedPath, result.getPath());
        assertEquals("/inventory/" + expectedHash, result.getGatewayPrefix());
        assertEquals("inventory/api", route.getPath());
        assertEquals("/inventory", route.getGatewayPrefix());
    }

    @Test
    void shouldReturnOriginalRouteWhenServiceFormattingIsNotRequired() {
        RouteRegistrationInfo senderRoute = route("http://sender/api", RouteType.EXTERNAL_SENDER)
                .toBuilder()
                .variableName("senderGatewayPrefix")
                .gatewayPrefix("/sender")
                .build();
        RouteRegistrationInfo serviceRouteWithoutVariable = route("http://service/api", RouteType.EXTERNAL_SERVICE)
                .toBuilder()
                .gatewayPrefix("/service")
                .build();

        assertSame(senderRoute, RouteRegistrationService.formatServiceRoutes(senderRoute));
        assertSame(serviceRouteWithoutVariable, RouteRegistrationService.formatServiceRoutes(serviceRouteWithoutVariable));
    }

    private static RouteRegistrationInfo route(String path, RouteType type) {
        return RouteRegistrationInfo.builder()
                .path(path)
                .type(type)
                .build();
    }
}
