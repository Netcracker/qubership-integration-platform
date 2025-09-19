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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;

import java.util.List;
import java.util.Map;

@ApplicationScoped
@IfBuildProperty(name = "qip.sds.enabled", stringValue = "true")
@OnAfterDeploymentContextCreated
public class SdsSchedulerJobsRegistrar implements DeploymentProcessingAction {
    private final SdsService sdsService;

    @Inject
    public SdsSchedulerJobsRegistrar(SdsService sdsService) {
        this.sdsService = sdsService;
    }

    @Override
    public void execute(
        CamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        List<Map<String, String>> sdsElementsProperties = deploymentConfiguration.getProperties()
            .stream()
            .filter(SdsSchedulerJobsRegistrar::isSdsTrigger)
            .map(ElementProperties::getProperties)
            .toList();
        sdsService.registerSchedulerJobs(context, deploymentInfo, sdsElementsProperties);
    }

    private static boolean isSdsTrigger(ElementProperties elementProperties) {
        Map<String, String> properties = elementProperties.getProperties();
        ChainElementType elementType = ChainElementType.fromString(properties.get(ChainProperties.ELEMENT_TYPE));
        return ChainElementType.isSdsTriggerElement(elementType);
    }
}
