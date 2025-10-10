package org.qubership.integration.platform.engine.service.deployment.processing.actions.delete.after;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.camel.repository.PerDeploymentBeanRepository;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterRoutesDeleted;

@ApplicationScoped
@OnAfterRoutesDeleted
public class RemoveDeploymentBeanRepository implements DeploymentProcessingAction {
    @Override
    public void execute(CamelContext context, DeploymentUpdate deploymentUpdate) {
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        context.getRegistry()
                .findSingleByType(PerDeploymentBeanRepository.class)
                .removeRegistry(deploymentId);
    }
}
