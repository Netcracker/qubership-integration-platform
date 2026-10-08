package org.qubership.integration.platform.engine.controlplane;

import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ChainRouteRegistry {
    private final Map<String, Map<String, List<DeploymentRouteUpdate>>> registrationsByChainId = new ConcurrentHashMap<>();

    public void register(String chainId, String deploymentId, List<DeploymentRouteUpdate> routes) {
        registrationsByChainId
                .computeIfAbsent(chainId, id -> new ConcurrentHashMap<>())
                .put(deploymentId, routes);
    }

    public List<DeploymentRouteUpdate> getUnsharedRoutes(String chainId, String deploymentId) {
        Map<String, List<DeploymentRouteUpdate>> byDeployment = registrationsByChainId.get(chainId);
        if (byDeployment == null) {
            return List.of();
        }
        List<DeploymentRouteUpdate> ownRoutes = byDeployment.get(deploymentId);
        if (ownRoutes == null) {
            return List.of();
        }
        Set<String> pathsClaimedByOthers = byDeployment.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(deploymentId))
                .flatMap(entry -> entry.getValue().stream())
                .map(ChainRouteRegistry::gatewayPath)
                .collect(Collectors.toSet());
        return ownRoutes.stream()
                .filter(route -> !pathsClaimedByOthers.contains(gatewayPath(route)))
                .toList();
    }

    // An egress route's path holds its target URL. The gateway matches the route on its gateway prefix.
    private static String gatewayPath(DeploymentRouteUpdate route) {
        return RouteType.isEgressRoute(route.getType()) ? route.getGatewayPrefix() : route.getPath();
    }

    public void unregister(String chainId, String deploymentId) {
        registrationsByChainId.computeIfPresent(chainId, (id, byDeployment) -> {
            byDeployment.remove(deploymentId);
            return byDeployment.isEmpty() ? null : byDeployment;
        });
    }
}
