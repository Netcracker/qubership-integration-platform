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

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotentRepositoryKeyStrategy;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotentRepositoryParameters;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Slf4j
@Component
@OnAfterDeploymentContextCreated
@ConditionalOnProperty(value = "qip.idempotency.enabled", havingValue = "true", matchIfMissing = true)
public class IdempotentConsumerDependencyBinder extends ElementProcessingAction {
    private final Function<IdempotentRepositoryParameters, IdempotentRepository> idempotentRepositoryFactory;

    @Autowired
    public IdempotentConsumerDependencyBinder(
        Function<IdempotentRepositoryParameters, IdempotentRepository> idempotentRepositoryFactory
    ) {
        this.idempotentRepositoryFactory = idempotentRepositoryFactory;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        String elementType = properties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        return (
            ChainElementType.HTTP_TRIGGER.equals(chainElementType)
            || ChainElementType.KAFKA_TRIGGER_2.equals(chainElementType)
            || ChainElementType.RABBITMQ_TRIGGER_2.equals(chainElementType)
            || ChainElementType.ASYNCAPI_TRIGGER.equals(chainElementType)
            || ChainElementType.JMS_TRIGGER.equals(chainElementType)
        ) && Boolean.valueOf(properties.getProperties().get(ChainProperties.IDEMPOTENCY_ENABLED));
    }

    @Override
    public void apply(SpringCamelContext context, ElementProperties properties, DeploymentInfo deploymentInfo) {
        String elementId = properties.getElementId();
        IdempotentRepositoryParameters keyParameters = IdempotentRepositoryParameters.builder()
            .ttl(Integer.valueOf(properties.getProperties().get(ChainProperties.EXPIRY)))
            .keyStrategy(getKeyStrategy(properties, deploymentInfo))
            .build();
        IdempotentRepository idempotentRepository = idempotentRepositoryFactory.apply(keyParameters);
        context.getRegistry().bind(elementId, idempotentRepository);
    }

    private IdempotentRepositoryKeyStrategy getKeyStrategy(
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        return key -> key;
    }

}
