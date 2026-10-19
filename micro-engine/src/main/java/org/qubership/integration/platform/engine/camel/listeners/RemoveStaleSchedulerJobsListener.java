package org.qubership.integration.platform.engine.camel.listeners;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.impl.event.CamelContextStartedEvent;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.SimpleEventNotifierSupport;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

// Camel does not unschedule a job in clustered mode, so the jobs of routes that no pod of the domain
// runs any more stay in the database and keep firing. The context starts after every route has committed its jobs.
@ApplicationScoped
@Unremovable
public class RemoveStaleSchedulerJobsListener extends SimpleEventNotifierSupport {
    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @Override
    public void notify(CamelEvent event) {
        if (event instanceof CamelContextStartedEvent startedEvent) {
            quartzSchedulerService.removeJobsNotOwnedBy(startedEvent.getContext().getRoutes());
        }
    }
}
