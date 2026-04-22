package org.qubership.integration.platform.engine.camel.listeners.actions.routes.stopped;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteStopped;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

@OnRouteStopped
@ApplicationScoped
public class ResetSchedulersProxyOnStopAction implements EventProcessingAction<CamelEvent.RouteStoppedEvent> {
    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @Override
    public void process(CamelEvent.RouteStoppedEvent event) throws Exception {
        quartzSchedulerService.resetSchedulersProxy();
    }
}
