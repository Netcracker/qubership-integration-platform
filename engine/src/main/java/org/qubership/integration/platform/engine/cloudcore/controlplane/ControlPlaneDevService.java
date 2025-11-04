package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.model.controlplane.v1.get.RouteConfigurationResponse;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;

@Component("controlPlaneService")
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "false", matchIfMissing = true)
public class ControlPlaneDevService implements ControlPlaneService {

    public List<RouteConfigurationResponse> getRoutesList() throws JsonProcessingException, ControlPlaneException, RestClientException {
        return Collections.emptyList();
    }

    @Override
    public void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint) throws ControlPlaneException {
    }

    @Override
    public void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint) throws ControlPlaneException {
    }

    @Override
    public void removeEngineRoutesByPathsAndEndpoint(List<Pair<String, RouteType>> paths, String deploymentName)
        throws ControlPlaneException {

    }

    @Override
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) {

    }
}
