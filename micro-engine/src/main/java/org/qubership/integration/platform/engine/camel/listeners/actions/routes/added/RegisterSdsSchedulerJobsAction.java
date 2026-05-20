package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.helpers.SdsSchedulerJobsRegistrationHelper;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteAdded;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.service.SdsService;

@Slf4j
@OnRouteAdded
@ApplicationScoped
@IfBuildProperty(name = "qip.sds.enabled", stringValue = "true")
public class RegisterSdsSchedulerJobsAction implements EventProcessingAction<CamelEvent.RouteAddedEvent> {
    @Inject
    SdsService sdsService;

    @Inject
    SdsSchedulerJobsRegistrationHelper sdsSchedulerJobsRegistrationHelper;

    @Override
    public void process(CamelEvent.RouteAddedEvent event) throws Exception {
        DeploymentInfo deploymentInfo = MetadataUtil.getBean(event.getRoute(), DeploymentInfo.class);
        MetadataUtil.getElementsInfo(event.getRoute())
                .filter(RegisterSdsSchedulerJobsAction::isSdsTrigger)
                .forEach(elementInfo -> {
                    sdsService.registerSchedulerJobs(deploymentInfo, elementInfo);
                    sdsSchedulerJobsRegistrationHelper.markRegistered(deploymentInfo, elementInfo);
                });
    }

    private static boolean isSdsTrigger(ElementInfo elementInfo) {
        ChainElementType elementType = ChainElementType.fromString(elementInfo.getType());
        return ChainElementType.isSdsTriggerElement(elementType);
    }
}
