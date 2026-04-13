package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;

import java.util.Optional;

@ApplicationScoped
@OnBeforeRoutesCreated
public class BlueGreenStateRequirementSchedulerCheckAction implements DeploymentProcessingAction {
    private final BlueGreenStateService blueGreenStateService;

    @Inject
    public BlueGreenStateRequirementSchedulerCheckAction(
        BlueGreenStateService blueGreenStateService
    ) {
        this.blueGreenStateService = blueGreenStateService;
    }

    @Override
    public void execute(CamelContext context, DeploymentUpdate deploymentUpdate) {
        if (!deploymentUpdate.getDeploymentInfo().isContainsSchedulerElements()) {
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
