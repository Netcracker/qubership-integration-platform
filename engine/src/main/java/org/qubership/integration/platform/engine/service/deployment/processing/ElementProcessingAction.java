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

package org.qubership.integration.platform.engine.service.deployment.processing;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.slf4j.MDC;

import java.util.Optional;

@Slf4j
public abstract class ElementProcessingAction implements DeploymentProcessingAction {
    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        Optional.ofNullable(deploymentConfiguration)
            .map(DeploymentConfiguration::getProperties)
            .ifPresent(properties -> properties.stream()
                    .filter(this::applicableTo)
                    .forEach(elementProperties -> processElement(context, elementProperties, deploymentInfo)));
    }

    private void processElement(
        SpringCamelContext context,
        ElementProperties elementProperties,
        DeploymentInfo deploymentInfo
    ) {
        try {
            String elementId = elementProperties.getProperties().get(ChainProperties.ELEMENT_ID);
            log.debug("Applying action {} for deployment {}, element {}",
                this.getClass().getSimpleName(), deploymentInfo.getDeploymentId(), elementId);
            MDC.put(ChainProperties.ELEMENT_ID, elementId);
            apply(context, elementProperties, deploymentInfo);
        } finally {
            MDC.remove(ChainProperties.ELEMENT_ID);
        }
    } 

    public abstract boolean applicableTo(ElementProperties properties);

    public abstract void apply(
        SpringCamelContext context,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    );
}
