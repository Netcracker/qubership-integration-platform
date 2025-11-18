package org.qubership.integration.platform.engine.service.deployment;

import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.model.deployment.engine.DeploymentStatus;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

public class DeploymentStateHolder {
    @Data
    @Builder(toBuilder = true)
    public static class DeploymentState {
        private DeploymentStatus status;
        private boolean suspended;
        private String errorMessage;
        private String chainStatusCode;
    }

    private final ConcurrentMap<String, Pair<DeploymentInfo, DeploymentState>> stateMap = new ConcurrentHashMap<>();

    public boolean has(String deploymentId) {
        return stateMap.containsKey(deploymentId);
    }

    public void remove(String deploymentId) {
        stateMap.remove(deploymentId);
    }

    public void put(DeploymentInfo deploymentInfo, DeploymentState deploymentState) {
        stateMap.put(deploymentInfo.getDeploymentId(), Pair.of(deploymentInfo, deploymentState));
    }

    public Stream<Pair<DeploymentInfo, DeploymentState>> values() {
        return stateMap.values().stream();
    }
}
