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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnBean(SdsService.class)
@OnBeforeDeploymentContextCreated
public class SdsConnectionCheckAction extends ElementProcessingAction {
    private final SdsService sdsService;

    @Autowired
    public SdsConnectionCheckAction(SdsService sdsService) {
        this.sdsService = sdsService;
    }
    
    @Override
    public boolean applicableTo(ElementProperties properties) {
        ChainElementType chainElementType = ChainElementType.fromString(
                properties.getProperties().get(ChainProperties.ELEMENT_TYPE));
        return ChainElementType.isSdsTriggerElement(chainElementType);
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        try {
            sdsService.getJobsMetadata();
        } catch (Exception exception) {
            log.warn("Sds trigger predeploy check failed. Please check scheduling-service");
            throw new DeploymentRetriableException(
                    "Sds trigger predeploy check failed. Please check scheduling-service",
                    exception);
        }
    }
}
