package org.qubership.integration.platform.engine.controlplane.impl;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.RouteType;

import java.util.List;

@ApplicationScoped
@DefaultBean
public class NoopControlPlaneService implements ControlPlaneService {
    @Override
    public void postPublicEngineRoutes(
            List<RouteRegistrationInfo> routes,
            String endpoint
    ) throws ControlPlaneException {
        // Do nothing
    }

    @Override
    public void postPrivateEngineRoutes(
            List<RouteRegistrationInfo> routes,
            String endpoint
    ) throws ControlPlaneException {
        // Do nothing
    }

    @Override
    public void removeEngineRoutesByPathsAndEndpoint(
            List<Pair<String, RouteType>> paths,
            String endpoint
    ) throws ControlPlaneException {
        // Do nothing
    }

    @Override
    public void postEgressGatewayRoutes(
            RouteRegistrationInfo route
    ) {
        // Do nothing
    }
}
