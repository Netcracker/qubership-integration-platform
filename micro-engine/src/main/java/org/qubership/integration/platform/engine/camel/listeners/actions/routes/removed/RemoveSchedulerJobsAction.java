package org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteRemoved;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

@OnRouteRemoved
@ApplicationScoped
public class RemoveSchedulerJobsAction implements EventProcessingAction<CamelEvent.RouteRemovedEvent> {
    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @Override
    public void process(CamelEvent.RouteRemovedEvent event) throws Exception {
        Route route = event.getRoute();
        quartzSchedulerService.removeSchedulerJobs(route);
    }
}
