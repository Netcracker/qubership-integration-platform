package org.qubership.integration.platform.engine.service.deployment.processing.actions.delete.before;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesDeleted;

@ApplicationScoped
@Priority(Integer.MIN_VALUE)
@OnBeforeRoutesDeleted
public class RemoveSchedulerJobsAction implements DeploymentProcessingAction {
    private final QuartzSchedulerService quartzSchedulerService;

    @Inject
    public RemoveSchedulerJobsAction(QuartzSchedulerService quartzSchedulerService) {
        this.quartzSchedulerService = quartzSchedulerService;
    }

    @Override
    public void execute(CamelContext context, DeploymentUpdate deploymentUpdate) {
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        quartzSchedulerService.removeSchedulerJobsFromContext(context, deploymentId);
    }
}
