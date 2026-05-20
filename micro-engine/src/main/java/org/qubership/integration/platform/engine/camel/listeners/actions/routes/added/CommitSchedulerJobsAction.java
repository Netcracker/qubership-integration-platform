package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteAdded;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

@OnRouteAdded
@ApplicationScoped
public class CommitSchedulerJobsAction implements EventProcessingAction<CamelEvent.RouteAddedEvent> {
    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @Override
    public void process(CamelEvent.RouteAddedEvent event) throws Exception {
        quartzSchedulerService.commitScheduledJobs();
    }
}
