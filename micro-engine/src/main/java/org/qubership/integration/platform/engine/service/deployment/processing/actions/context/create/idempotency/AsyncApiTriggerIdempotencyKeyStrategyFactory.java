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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.idempotency;

import org.qubership.integration.platform.engine.camel.idempotency.IdempotentRepositoryKeyStrategyBuilder;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AsyncApiTriggerIdempotencyKeyStrategyFactory extends IdempotencyKeyStrategyFactoryBase {
    private final RabbitMQTriggerIdempotencyKeyStrategyFactory rabbitMQTriggerIdempotencyKeyStrategyFactory;
    private final KafkaTriggerIdempotencyKeyStrategyFactory kafkaTriggerIdempotencyKeyStrategyFactory;

    @Autowired
    public AsyncApiTriggerIdempotencyKeyStrategyFactory(
        RabbitMQTriggerIdempotencyKeyStrategyFactory rabbitMQTriggerIdempotencyKeyStrategyFactory,
        KafkaTriggerIdempotencyKeyStrategyFactory kafkaTriggerIdempotencyKeyStrategyFactory
    ) {
        this.rabbitMQTriggerIdempotencyKeyStrategyFactory = rabbitMQTriggerIdempotencyKeyStrategyFactory;
        this.kafkaTriggerIdempotencyKeyStrategyFactory = kafkaTriggerIdempotencyKeyStrategyFactory;
    }

    @Override
    public Collection<ChainElementType> getElementTypes() {
        return Set.of(ChainElementType.ASYNCAPI_TRIGGER);
    }

    @Override
    protected void configureStrategy(
        IdempotentRepositoryKeyStrategyBuilder builder,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        Map<String, String> props = properties.getProperties();
        String protocolType = props.get(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP);
        if (ChainProperties.OPERATION_PROTOCOL_TYPE_KAFKA.equals(protocolType)) {
            kafkaTriggerIdempotencyKeyStrategyFactory.configureStrategy(builder, properties, deploymentInfo);
        } else if (ChainProperties.OPERATION_PROTOCOL_TYPE_AMQP.equals(protocolType)) {
            rabbitMQTriggerIdempotencyKeyStrategyFactory.configureStrategy(builder, properties, deploymentInfo);
        }
    }
}
