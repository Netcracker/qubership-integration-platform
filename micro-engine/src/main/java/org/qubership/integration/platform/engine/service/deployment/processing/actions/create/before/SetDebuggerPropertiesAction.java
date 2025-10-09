package org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;

@ApplicationScoped
@OnBeforeRoutesCreated
public class SetDebuggerPropertiesAction implements DeploymentProcessingAction {
    private final CamelDebuggerPropertiesService propertiesService;

    @Inject
    public SetDebuggerPropertiesAction(CamelDebuggerPropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    @Override
    public void execute(
            CamelContext context,
            DeploymentUpdate deploymentUpdate
    ) {
        propertiesService.mergeWithRuntimeProperties(CamelDebuggerProperties.builder()
                .deploymentInfo(deploymentUpdate.getDeploymentInfo())
                .maskedFields(deploymentUpdate.getMaskedFields())
                .properties(deploymentUpdate.getConfiguration().getProperties())
                .build());
    }
}
