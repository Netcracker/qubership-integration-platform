package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@OnBeforeDeploymentContextCreated
public class BlueGreenStateRequirementSchedulerCheckAction implements DeploymentProcessingAction {
    private BlueGreenStateService blueGreenStateService;

    @Autowired
    public BlueGreenStateRequirementSchedulerCheckAction(
        BlueGreenStateService blueGreenStateService
    ) {
        this.blueGreenStateService = blueGreenStateService;
    }

    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        if (!deploymentInfo.isContainsSchedulerElements()) {
            return;
        }

        BlueGreenState bgState = blueGreenStateService.getBlueGreenState();
        if (!BlueGreenStateService.isActive(bgState)) {
            String stateName = Optional.ofNullable(bgState)
                .map(BlueGreenState::getCurrent)
                .map(NamespaceVersion::getState)
                .map(State::getName)
                .orElse("null");
            throw new DeploymentRetriableException(
                "Chain with scheduler can't be deployed in blue-green state: '"
                    + stateName + "', waiting for 'active' state");
        }
    }
}
