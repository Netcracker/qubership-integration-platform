package org.qubership.integration.platform.engine.controlplane;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainRouteRegistryTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID_A = "deployment-a";
    private static final String DEPLOYMENT_ID_B = "deployment-b";

    private final ChainRouteRegistry registry = new ChainRouteRegistry();

    @Test
    void getIfCurrentOwnerReturnsRegisteredRoutesForOwningDeployment() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);

        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(result.isPresent());
        assertEquals(routes, result.get());
    }

    @Test
    void getIfCurrentOwnerReturnsEmptyForNonOwningDeployment() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B);

        assertTrue(result.isEmpty());
    }

    @Test
    void getIfCurrentOwnerReturnsEmptyWhenChainWasNeverRegistered() {
        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(result.isEmpty());
    }

    @Test
    void registerOverwritesPreviousDeploymentForSameChain() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        assertTrue(registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B);
        assertTrue(result.isPresent());
        assertEquals("/new", result.get().get(0).getPath());
    }

    @Test
    void clearIfCurrentOwnerRemovesEntryWhenDeploymentIdMatches() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        registry.clearIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
    }

    @Test
    void clearIfCurrentOwnerDoesNothingWhenDeploymentIdDoesNotMatch() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        // A stale clear from the superseded deployment must not clear the newer registration.
        registry.clearIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B);
        assertTrue(result.isPresent());
        assertEquals("/new", result.get().get(0).getPath());
    }

    @Test
    void clearIfCurrentOwnerDoesNothingWhenChainWasNeverRegistered() {
        assertDoesNotThrow(() -> registry.clearIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A));
    }

    private DeploymentRouteUpdate route(String path) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(RouteType.EXTERNAL_TRIGGER)
                .build();
    }
}
