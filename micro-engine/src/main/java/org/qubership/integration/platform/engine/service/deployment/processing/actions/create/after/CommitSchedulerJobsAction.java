package org.qubership.integration.platform.engine.service.deployment.processing.actions.create.after;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterRoutesCreated;

@ApplicationScoped
@Priority(Integer.MIN_VALUE)
@OnAfterRoutesCreated
public class CommitSchedulerJobsAction implements DeploymentProcessingAction {
    private final QuartzSchedulerService quartzSchedulerService;

    public CommitSchedulerJobsAction(QuartzSchedulerService quartzSchedulerService) {
        this.quartzSchedulerService = quartzSchedulerService;
    }

    @Override
    public void execute(CamelContext context, DeploymentUpdate deploymentUpdate) {
        quartzSchedulerService.commitScheduledJobs();
    }
}
