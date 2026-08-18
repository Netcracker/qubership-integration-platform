package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.SERVICE_CALL_COMPONENT;

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

    // Never actually evaluated: elementRepository is mocked, so the predicate body is irrelevant --
    // only a real (non-null) Specification instance is needed for Specification.and(...) to compose.
    private static final Specification<ChainElement> ANY_SPEC = (root, query, cb) -> null;

    private ElementRepository elementRepository;
    private SystemService systemService;
    private RoutesGetterService routesGetterService;

    @BeforeEach
    void setUp() {
        elementRepository = mock(ElementRepository.class);
        systemService = mock(SystemService.class);
        routesGetterService = new RoutesGetterService(elementRepository, systemService);
        ReflectionTestUtils.setField(routesGetterService, "registerOnEgress", true);
        ReflectionTestUtils.setField(routesGetterService, "registerOnIncomingGateways", false);
    }

    private ChainElement serviceCallElement(String originalId, String systemId) {
        return ChainElement.builder()
                .originalId(originalId)
                .type(SERVICE_CALL_COMPONENT)
                .properties(Map.of(CamelOptions.SYSTEM_ID, systemId))
                .build();
    }

    private void stubSystem(IntegrationSystem system, Environment environment, Long connectTimeout) {
        when(systemService.findSystemsRequiredGatewayRoutes(anyCollection())).thenReturn(List.of(system));
        when(systemService.getActiveEnvironment(system)).thenReturn(environment);
        when(systemService.getActiveEnvAddress(environment)).thenReturn(environment.getAddress());
        when(systemService.getConnectTimeout(environment)).thenReturn(connectTimeout);
    }

    // No senders in these fixtures, but buildHttpSendersRoutes still runs first (registerOnEgress
    // is true) and queries the repository once before buildServicesRoutes does.
    private void stubElements(List<ChainElement> serviceCallElements) {
        when(elementRepository.findAll(any(Specification.class)))
                .thenReturn(List.of())
                .thenReturn(serviceCallElements);
    }

    @Test
    void externalServiceRouteKeepsRawAddressAndUnhashedGatewayPrefix() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .integrationSystemType(IntegrationSystemType.EXTERNAL)
                .build();
        Environment environment = Environment.builder().address("example.com").build();
        stubSystem(system, environment, 5000L);
        stubElements(List.of(serviceCallElement("elem-a", SYSTEM_ID)));

        List<DeploymentRoute> routes = routesGetterService.getRoutes(ANY_SPEC);

        assertEquals(1, routes.size());
        DeploymentRoute route = routes.get(0);
        assertEquals(RouteType.EXTERNAL_SERVICE, route.getType());
        assertEquals("example.com", route.getPath());
        assertEquals("/system/elem-a", route.getGatewayPrefix());
    }

    @Test
    void twoElementsOnTheSameSystemShareTheSameUnhashedPrefixPattern() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .integrationSystemType(IntegrationSystemType.EXTERNAL)
                .build();
        Environment environment = Environment.builder().address("https://api.example.com").build();
        stubSystem(system, environment, 3000L);
        stubElements(List.of(
                serviceCallElement("elem-a", SYSTEM_ID),
                serviceCallElement("elem-b", SYSTEM_ID)));

        List<DeploymentRoute> routes = routesGetterService.getRoutes(ANY_SPEC);

        assertEquals(2, routes.size());
        assertEquals("/system/elem-a", routes.get(0).getGatewayPrefix());
        assertEquals("/system/elem-b", routes.get(1).getGatewayPrefix());
        assertEquals("https://api.example.com", routes.get(0).getPath());
        assertEquals("https://api.example.com", routes.get(1).getPath());
    }
}
