package org.qubership.integration.platform.engine.service.deployment;

import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class DeploymentRetryQueue {
    private final AtomicReference<Collection<DeploymentUpdate>> queue =
            new AtomicReference<>(Collections.emptyList());

    public Collection<DeploymentUpdate> flush() {
        return queue.getAndSet(Collections.emptyList());
    }

    public void put(DeploymentUpdate deployment) {
        queue.updateAndGet(collection -> {
            List<DeploymentUpdate> result = new ArrayList<>(collection);
            result.add(deployment);
            return result;
        });
    }

    public void remove(String deploymentId) {
        queue.updateAndGet(collection -> collection.stream()
                .filter(deploymentUpdate -> !deploymentId.equals(
                        deploymentUpdate.getDeploymentInfo().getDeploymentId()))
                .toList());
    }
}
