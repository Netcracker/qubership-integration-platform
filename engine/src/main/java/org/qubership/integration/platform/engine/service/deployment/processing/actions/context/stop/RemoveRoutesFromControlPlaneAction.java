/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.stop;

import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnStopDeploymentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@OnStopDeploymentContext
public class RemoveRoutesFromControlPlaneAction implements DeploymentProcessingAction {
    private final ControlPlaneService controlPlaneService;
    private final ApplicationAutoConfiguration applicationConfiguration;

    @Autowired
    public RemoveRoutesFromControlPlaneAction(
        ControlPlaneService controlPlaneService,
        ApplicationAutoConfiguration applicationConfiguration
    ) {
        this.controlPlaneService = controlPlaneService;
        this.applicationConfiguration = applicationConfiguration;
    }

    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        try {
            controlPlaneService.removeEngineRoutes(
                deploymentConfiguration.getRoutes().stream()
                    .filter(route -> RouteType.triggerRouteWithGateway(route.getType()))
                    .toList(),
                applicationConfiguration.getDeploymentName());
        } catch (ControlPlaneException e) {
            throw new DeploymentRetriableException(e);
        }
    }
}
