package org.qubership.integration.platform.engine.controlplane;

import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChainRouteRegistry {
    private final Map<String, Registration> registrationsByChainId = new ConcurrentHashMap<>();

    public void register(String chainId, String deploymentId, List<DeploymentRouteUpdate> routes) {
        registrationsByChainId.put(chainId, new Registration(deploymentId, routes));
    }

    public Optional<List<DeploymentRouteUpdate>> getIfCurrentOwner(String chainId, String deploymentId) {
        Registration current = registrationsByChainId.get(chainId);
        return (current != null && current.deploymentId().equals(deploymentId))
                ? Optional.of(current.routes())
                : Optional.empty();
    }

    public void clearIfCurrentOwner(String chainId, String deploymentId) {
        registrationsByChainId.computeIfPresent(chainId,
                (id, reg) -> reg.deploymentId().equals(deploymentId) ? null : reg);
    }

    private record Registration(String deploymentId, List<DeploymentRouteUpdate> routes) {
    }
}
