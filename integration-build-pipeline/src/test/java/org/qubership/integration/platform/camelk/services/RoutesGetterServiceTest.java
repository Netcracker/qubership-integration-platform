package org.qubership.integration.platform.camelk.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.model.routes.RouteType;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.chain.model.ServiceType;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.library.constants.CamelNames.SERVICE_CALL_COMPONENT;

/**
 * Verifies {@link RoutesGetterService#getRoutes} builds EXTERNAL_SERVICE routes with the RAW
 * environment address and an UN-hashed {@code gatewayPrefix} ({@code /system/{elementId}}).
 * This is deliberate, not an oversight: this method's output also feeds the deployment payload
 * engine/micro-engine receive, and their own {@code formatServiceRoutes}/equivalent unconditionally
 * appends a scheme-normalization + SHA-1 hash downstream of this method. Pre-hashing here would
 * double that transformation for the live-registration path. Build-time CR generation applies the
 * same transformation itself, locally, via {@code EgressServiceRouteFormatter} -- see
 * {@code EgressServiceRouteFormatterTest} for that half of the contract.
 */
class RoutesGetterServiceTest {

    private static final String SYSTEM_ID = "sys-1";

    private IntegrationServiceCatalog serviceCatalog;
    private RoutesGetterService routesGetterService;

    @BeforeEach
    void setUp() {
        serviceCatalog = mock(IntegrationServiceCatalog.class);
        routesGetterService = new RoutesGetterService();
        ReflectionTestUtils.setField(routesGetterService, "registerOnEgress", true);
        ReflectionTestUtils.setField(routesGetterService, "registerOnIncomingGateways", false);
    }

    private Element serviceCallElement(String originalId, String systemId) {
        return ElementBuilder.createNew()
                .id(originalId)
                .originalId(originalId)
                .type(SERVICE_CALL_COMPONENT)
                .properties(Map.of(CamelOptions.SYSTEM_ID, systemId))
                .build();
    }

    private Snapshot snapshotWith(List<Element> elements) {
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getElements()).thenReturn(elements);
        return snapshot;
    }

    private void stubExternalService(String address, Long connectTimeout) {
        ServiceEnvironment environment = mock(ServiceEnvironment.class);
        when(environment.getAddress()).thenReturn(address);
        when(environment.getProperties())
                .thenReturn(Map.of(CamelOptions.CONNECT_TIMEOUT, connectTimeout));

        IntegrationService service = mock(IntegrationService.class);
        when(service.getId()).thenReturn(SYSTEM_ID);
        when(service.getType()).thenReturn(ServiceType.EXTERNAL);
        when(service.getProtocol()).thenReturn(Protocol.HTTP);
        when(service.getActiveEnvironment()).thenReturn(Optional.of(environment));

        when(serviceCatalog.findAllByIds(anyCollection())).thenReturn(List.of(service));
    }

    @Test
    void externalServiceRouteKeepsRawAddressAndUnhashedGatewayPrefix() {
        stubExternalService("example.com", 5000L);
        Snapshot snapshot = snapshotWith(List.of(serviceCallElement("elem-a", SYSTEM_ID)));

        List<Route> routes = routesGetterService.getRoutes(snapshot, serviceCatalog);

        assertEquals(1, routes.size());
        Route route = routes.get(0);
        assertEquals(RouteType.EXTERNAL_SERVICE, route.getType());
        assertEquals("example.com", route.getPath());
        assertEquals("/system/elem-a", route.getGatewayPrefix());
    }

    @Test
    void twoElementsOnTheSameSystemShareTheSameUnhashedPrefixPattern() {
        stubExternalService("https://api.example.com", 3000L);
        Snapshot snapshot = snapshotWith(List.of(
                serviceCallElement("elem-a", SYSTEM_ID),
                serviceCallElement("elem-b", SYSTEM_ID)));

        List<Route> routes = routesGetterService.getRoutes(snapshot, serviceCatalog);

        assertEquals(2, routes.size());
        assertEquals("/system/elem-a", routes.get(0).getGatewayPrefix());
        assertEquals("/system/elem-b", routes.get(1).getGatewayPrefix());
        assertEquals("https://api.example.com", routes.get(0).getPath());
        assertEquals("https://api.example.com", routes.get(1).getPath());
    }
}
