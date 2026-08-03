package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.stop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before.RegisterRoutesInControlPlaneAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoveRoutesFromControlPlaneActionTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID_A = "deployment-a";
    private static final String DEPLOYMENT_ID_B = "deployment-b";
    private static final String DEPLOYMENT_NAME = "engine-service";

    private ControlPlaneService controlPlaneService;
    private ApplicationAutoConfiguration applicationConfiguration;
    private ChainRouteRegistry chainRouteRegistry;
    private RemoveRoutesFromControlPlaneAction removeAction;

    @BeforeEach
    void setUp() {
        controlPlaneService = mock(ControlPlaneService.class);
        applicationConfiguration = mock(ApplicationAutoConfiguration.class);
        when(applicationConfiguration.getDeploymentName()).thenReturn(DEPLOYMENT_NAME);
        chainRouteRegistry = new ChainRouteRegistry();
        removeAction = new RemoveRoutesFromControlPlaneAction(
                controlPlaneService, applicationConfiguration, chainRouteRegistry);
    }

    @Test
    void doesNotThrowWhenDeploymentConfigurationIsNull() {
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        assertDoesNotThrow(() ->
                removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null));
    }

    @Test
    void removesRoutesRegisteredByTheSameDeployment() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService).removeEngineRoutes(routes, DEPLOYMENT_NAME);
    }

    @Test
    void clearsTheRegistryEntryAfterASuccessfulRemoval() {
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        assertTrue(chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
    }

    @Test
    void doesNothingWhenNothingWasEverRegisteredForTheChain() {
        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
    }

    @Test
    void doesNothingWhenANewerDeploymentHasAlreadySupersededThisOne() {
        // Simulates a redeploy: deployment A's routes get overwritten by deployment B's
        // registration before A's stop action runs (register-then-stop ordering).
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
        assertTrue(chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B).isPresent());
    }

    @Test
    void leavesTheRegistryEntryInPlaceWhenRemovalFailsSoARetryCanFindItAgain() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);
        doThrow(new ControlPlaneException("boom"))
                .when(controlPlaneService).removeEngineRoutes(routes, DEPLOYMENT_NAME);

        assertThrows(DeploymentRetriableException.class, () ->
                removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null));

        assertTrue(chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isPresent());
    }

    @Test
    void endToEndRedeployOrderingDoesNotDeleteTheNewDeploymentsJustRegisteredRoutes() {
        // Reproduces the real IntegrationRuntimeService.update() ordering: the new
        // deployment registers first (via the real RegisterRoutesInControlPlaneAction,
        // sharing this same registry), then the old deployment's stop action runs.
        VariablesService variablesService = mock(VariablesService.class);
        RegisterRoutesInControlPlaneAction registerAction = new RegisterRoutesInControlPlaneAction(
                variablesService, controlPlaneService, applicationConfiguration, chainRouteRegistry);

        DeploymentRouteUpdate sameTriggerPath = route("/chain-1", RouteType.EXTERNAL_TRIGGER);
        DeploymentConfiguration configuration = DeploymentConfiguration.builder()
                .routes(List.of(sameTriggerPath))
                .build();

        // Deployment A originally registered this same path (simulating the prior deploy).
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(sameTriggerPath));

        // Deployment B (the new redeploy) registers the identical trigger path.
        registerAction.execute(null, deploymentInfo(DEPLOYMENT_ID_B), configuration);
        clearInvocations(controlPlaneService);

        // Now the old deployment A's stop runs, as IntegrationRuntimeService does after
        // the new context has already started.
        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
    }

    private DeploymentInfo deploymentInfo(String deploymentId) {
        return DeploymentInfo.builder()
                .deploymentId(deploymentId)
                .chainId(CHAIN_ID)
                .build();
    }

    private DeploymentRouteUpdate route(String path) {
        return route(path, RouteType.EXTERNAL_TRIGGER);
    }

    private DeploymentRouteUpdate route(String path, RouteType type) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(type)
                .build();
    }
}
