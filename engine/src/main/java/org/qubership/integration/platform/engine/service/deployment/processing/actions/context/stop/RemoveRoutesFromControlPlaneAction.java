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
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.RouteRegistrationException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnStopDeploymentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@OnStopDeploymentContext
public class RemoveRoutesFromControlPlaneAction implements DeploymentProcessingAction {
    private final ControlPlaneService controlPlaneService;
    private final ApplicationAutoConfiguration applicationConfiguration;
    private final ChainRouteRegistry chainRouteRegistry;

    @Autowired
    public RemoveRoutesFromControlPlaneAction(
        ControlPlaneService controlPlaneService,
        ApplicationAutoConfiguration applicationConfiguration,
        ChainRouteRegistry chainRouteRegistry
    ) {
        this.controlPlaneService = controlPlaneService;
        this.applicationConfiguration = applicationConfiguration;
        this.chainRouteRegistry = chainRouteRegistry;
    }

    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        String chainId = deploymentInfo.getChainId();
        String deploymentId = deploymentInfo.getDeploymentId();

        List<DeploymentRouteUpdate> routesToRemove = chainRouteRegistry.getUnsharedRoutes(chainId, deploymentId);
        try {
            if (!routesToRemove.isEmpty()) {
                controlPlaneService.removeEngineRoutes(routesToRemove, applicationConfiguration.getDeploymentName());
            }
            chainRouteRegistry.unregister(chainId, deploymentId);
        } catch (ControlPlaneException e) {
            throw new RouteRegistrationException("Failed to remove control plane routes for chain " + chainId, e);
        }
    }
}
