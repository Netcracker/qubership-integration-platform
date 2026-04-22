package org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.helpers.SdsSchedulerJobsRegistrationHelper;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteRemoved;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.service.SdsService;

@Slf4j
@OnRouteRemoved
@ApplicationScoped
@IfBuildProperty(name = "qip.sds.enabled", stringValue = "true")
public class RemoveSdsSchedulerJobsAction implements EventProcessingAction<CamelEvent.RouteRemovedEvent> {
    @Inject
    SdsService sdsService;

    @Inject
    SdsSchedulerJobsRegistrationHelper sdsSchedulerJobsRegistrationHelper;

    @Override
    public void process(CamelEvent.RouteRemovedEvent event) throws Exception {
        DeploymentInfo deploymentInfo = MetadataUtil.getBean(event.getRoute(), DeploymentInfo.class);
        sdsService.removeSchedulerJobs(deploymentInfo);
        sdsSchedulerJobsRegistrationHelper.markUnregistered(deploymentInfo);
    }
}
