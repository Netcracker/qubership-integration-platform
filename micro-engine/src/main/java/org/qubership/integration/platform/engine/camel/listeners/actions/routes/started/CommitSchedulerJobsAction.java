package org.qubership.integration.platform.engine.camel.listeners.actions.routes.started;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteStarted;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

// A poll consumer, such as an SFTP trigger, schedules its job when it starts, after RouteAddedEvent.
@OnRouteStarted
@ApplicationScoped
public class CommitSchedulerJobsAction implements EventProcessingAction<CamelEvent.RouteStartedEvent> {
    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @Override
    public void process(CamelEvent.RouteStartedEvent event) throws Exception {
        quartzSchedulerService.commitScheduledJobs();
    }
}
