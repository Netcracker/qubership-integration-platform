package org.qubership.integration.platform.engine.controlplane.impl;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;

import java.util.List;

@ApplicationScoped
@DefaultBean
public class NoopControlPlaneService implements ControlPlaneService {
    @Override
    public void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName) throws ControlPlaneException {
        // Do nothing
    }

    @Override
    public void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName) throws ControlPlaneException {
        // Do nothing
    }

    @Override
    public void removeEngineRoutesByPathsAndEndpoint(List<Pair<String, RouteType>> paths, String deploymentName) throws ControlPlaneException {
        // Do nothing
    }

    @Override
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) {
        // Do nothing
    }
}
