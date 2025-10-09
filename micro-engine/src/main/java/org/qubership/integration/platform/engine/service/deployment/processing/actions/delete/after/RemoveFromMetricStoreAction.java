package org.qubership.integration.platform.engine.service.deployment.processing.actions.delete.after;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterRoutesDeleted;

@ApplicationScoped
@OnAfterRoutesDeleted
public class RemoveFromMetricStoreAction implements DeploymentProcessingAction {
    private final MetricsStore metricsStore;

    @Inject
    public RemoveFromMetricStoreAction(MetricsStore metricsStore) {
        this.metricsStore = metricsStore;
    }

    @Override
    public void execute(CamelContext context, DeploymentUpdate deploymentUpdate) {
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        metricsStore.removeChainsDeployments(deploymentId);
    }
}
