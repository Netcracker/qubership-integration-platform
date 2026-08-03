package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.VariablesService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisterRoutesInControlPlaneActionTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID = "deployment-1";
    private static final String DEPLOYMENT_NAME = "engine-service";

    private VariablesService variablesService;
    private ControlPlaneService controlPlaneService;
    private ChainRouteRegistry chainRouteRegistry;
    private RegisterRoutesInControlPlaneAction action;

    @BeforeEach
    void setUp() {
        variablesService = mock(VariablesService.class);
        controlPlaneService = mock(ControlPlaneService.class);
        ApplicationAutoConfiguration applicationConfiguration = mock(ApplicationAutoConfiguration.class);
        when(applicationConfiguration.getDeploymentName()).thenReturn(DEPLOYMENT_NAME);
        chainRouteRegistry = new ChainRouteRegistry();
        action = new RegisterRoutesInControlPlaneAction(
                variablesService, controlPlaneService, applicationConfiguration, chainRouteRegistry);
    }

    @Test
    void registersGatewayTriggerRoutesInTheChainRouteRegistryAfterPosting() {
        DeploymentRouteUpdate publicRoute = route("/public", RouteType.EXTERNAL_TRIGGER);
        DeploymentRouteUpdate privateRoute = route("/private", RouteType.PRIVATE_TRIGGER);
        DeploymentConfiguration configuration = configuration(publicRoute, privateRoute);

        action.execute(null, deploymentInfo(), configuration);

        List<DeploymentRouteUpdate> registered =
                chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID);
        assertEquals(List.of("/public", "/private"), registered.stream()
                .map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void registersAnEmptyListWhenThereAreNoGatewayTriggerRoutes() {
        DeploymentRouteUpdate internalRoute = route("/internal", RouteType.INTERNAL_TRIGGER);
        DeploymentConfiguration configuration = configuration(internalRoute);

        action.execute(null, deploymentInfo(), configuration);

        List<DeploymentRouteUpdate> registered =
                chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID);
        assertTrue(registered.isEmpty());
    }

    @Test
    void postsPublicAndPrivateRoutesToTheirRespectiveTiers() {
        DeploymentRouteUpdate publicRoute = route("/public", RouteType.EXTERNAL_TRIGGER);
        DeploymentRouteUpdate privateRoute = route("/private", RouteType.PRIVATE_TRIGGER);
        DeploymentConfiguration configuration = configuration(publicRoute, privateRoute);

        action.execute(null, deploymentInfo(), configuration);

        verify(controlPlaneService).postPublicEngineRoutes(eq(List.of(publicRoute)), eq(DEPLOYMENT_NAME));
        verify(controlPlaneService).postPrivateEngineRoutes(eq(List.of(privateRoute)), eq(DEPLOYMENT_NAME));
    }

    private DeploymentInfo deploymentInfo() {
        return DeploymentInfo.builder()
                .deploymentId(DEPLOYMENT_ID)
                .chainId(CHAIN_ID)
                .build();
    }

    private DeploymentConfiguration configuration(DeploymentRouteUpdate... routes) {
        return DeploymentConfiguration.builder()
                .routes(List.of(routes))
                .build();
    }

    private DeploymentRouteUpdate route(String path, RouteType type) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(type)
                .build();
    }
}
