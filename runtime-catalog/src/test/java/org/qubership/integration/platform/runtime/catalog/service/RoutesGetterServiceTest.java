package org.qubership.integration.platform.runtime.catalog.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.SERVICE_CALL_COMPONENT;

/**
 * Verifies that {@link RoutesGetterService#getRoutes} builds EXTERNAL_SERVICE routes exactly the
 * way engine's {@code RegisterRoutesInControlPlaneAction.formatServiceRoutes} does: a
 * scheme-normalized path and a SHA-1 hash of (path, connectTimeout) appended to the gateway
 * prefix. Both write to the same shared HTTPRoute by name, so they must produce identical values
 * for the same underlying route.
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
    void externalServiceRouteGetsSchemeAndHashSuffixApplied() {
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
        assertEquals("https://example.com", route.getPath());
        assertTrue(route.getGatewayPrefix().matches("^/system/elem-a/[0-9a-f]{40}$"),
                () -> "unexpected gatewayPrefix: " + route.getGatewayPrefix());
    }

    @Test
    void twoElementsOnTheSameSystemShareTheHashSuffixButHaveDistinctPrefixes() {
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
        String hashA = routes.get(0).getGatewayPrefix().substring("/system/elem-a/".length());
        String hashB = routes.get(1).getGatewayPrefix().substring("/system/elem-b/".length());
        assertEquals(hashA, hashB);
        assertNotEquals(routes.get(0).getGatewayPrefix(), routes.get(1).getGatewayPrefix());
        assertEquals("/system/elem-a/" + hashA, routes.get(0).getGatewayPrefix());
        assertEquals("/system/elem-b/" + hashB, routes.get(1).getGatewayPrefix());
    }

    @Test
    void hashMatchesEngineAlgorithmExactly() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .integrationSystemType(IntegrationSystemType.EXTERNAL)
                .build();
        Environment environment = Environment.builder().address("backend.internal:8443/base").build();
        stubSystem(system, environment, 7000L);
        stubElements(List.of(serviceCallElement("elem-a", SYSTEM_ID)));

        List<DeploymentRoute> routes = routesGetterService.getRoutes(ANY_SPEC);

        String formattedPath = "https://backend.internal:8443/base";
        String expectedHash = DigestUtils.sha1Hex(StringUtils.joinWith(",", formattedPath, 7000L));
        assertEquals(formattedPath, routes.get(0).getPath());
        assertEquals("/system/elem-a/" + expectedHash, routes.get(0).getGatewayPrefix());
    }
}
