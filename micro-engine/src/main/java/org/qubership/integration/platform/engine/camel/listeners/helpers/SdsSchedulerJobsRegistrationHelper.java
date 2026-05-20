package org.qubership.integration.platform.engine.camel.listeners.helpers;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@IfBuildProperty(name = "qip.sds.enabled", stringValue = "true")
public class SdsSchedulerJobsRegistrationHelper {
    // chain ID -> [element ID]
    private final Map<String, Set<String>> registeredJobs = new ConcurrentHashMap<>();

    public boolean registered(DeploymentInfo deploymentInfo) {
        return registeredJobs.containsKey(deploymentInfo.getChain().getId());
    }

    public boolean registered(DeploymentInfo deploymentInfo, ElementInfo elementInfo) {
        return registeredJobs.getOrDefault(deploymentInfo.getChain().getId(), Collections.emptySet())
                .contains(elementInfo.getId());
    }

    public void markRegistered(DeploymentInfo deploymentInfo, ElementInfo elementInfo) {
        registeredJobs.compute(deploymentInfo.getChain().getId(), (key, value) -> {
            if (value == null) {
                return Collections.singleton(elementInfo.getId());
            } else {
                Set<String> result = new HashSet<>(value);
                result.add(elementInfo.getId());
                return result;
            }
        });
    }

    public void markUnregistered(DeploymentInfo deploymentInfo) {
        registeredJobs.remove(deploymentInfo.getId());
    }
}
