package org.qubership.integration.platform.engine.controlplane;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainRouteRegistryTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID_A = "deployment-a";
    private static final String DEPLOYMENT_ID_B = "deployment-b";

    private final ChainRouteRegistry registry = new ChainRouteRegistry();

    @Test
    void getUnsharedRoutesReturnsAllRoutesWhenNoOtherDeploymentIsRegisteredForTheChain() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A);

        assertEquals(routes, result);
    }

    @Test
    void getUnsharedRoutesReturnsEmptyListForADeploymentThatNeverRegistered() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B);

        assertTrue(result.isEmpty());
    }

    @Test
    void getUnsharedRoutesReturnsEmptyListWhenTheChainWasNeverRegistered() {
        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(result.isEmpty());
    }

    @Test
    void getUnsharedRoutesExcludesPathsClaimedByAnotherRegisteredDeployment() {
        // A and B both claim /shared (the redeploy overlap window); A alone claims /old-only.
        DeploymentRouteUpdate shared = route("/shared");
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(shared, route("/old-only")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(shared, route("/new-only")));

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A);

        assertEquals(List.of("/old-only"), result.stream().map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void getUnsharedRoutesIsSymmetricFromTheOtherDeploymentsSide() {
        // Same registry state as above, computed from B's side: the C-1 scenario, where B's
        // own start-failure teardown must not remove A's still-running route.
        DeploymentRouteUpdate shared = route("/shared");
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(shared, route("/old-only")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(shared, route("/new-only")));

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B);

        assertEquals(List.of("/new-only"), result.stream().map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void registerOverwritesThisDeploymentsPreviousRoutesWithoutAffectingOtherDeployments() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/first-attempt")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/other")));

        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/retried-attempt")));

        assertEquals(List.of("/retried-attempt"),
                registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).stream()
                        .map(DeploymentRouteUpdate::getPath).toList());
        assertEquals(List.of("/other"),
                registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B).stream()
                        .map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void unregisterRemovesOnlyTheNamedDeploymentsEntryLeavingOthersIntact() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        registry.unregister(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
        assertEquals(List.of("/new"),
                registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B).stream()
                        .map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void unregisterDoesNothingWhenTheChainWasNeverRegistered() {
        assertDoesNotThrow(() -> registry.unregister(CHAIN_ID, DEPLOYMENT_ID_A));
    }

    private DeploymentRouteUpdate route(String path) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(RouteType.EXTERNAL_TRIGGER)
                .build();
    }
}
