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

package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.OPERATION_PATH_TOPIC;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.SASL_MECHANISM;

@Component
public class OperationElementPropertiesBuilder implements ElementPropertiesBuilder {

    private final KafkaElementPropertiesBuilder kafkaElementPropertiesBuilder;
    private final RabbitMqElementPropertiesBuilder rabbitMqElementPropertiesBuilder;

    @Autowired
    public OperationElementPropertiesBuilder(KafkaElementPropertiesBuilder kafkaElementPropertiesBuilder, RabbitMqElementPropertiesBuilder rabbitMqElementPropertiesBuilder) {
        this.kafkaElementPropertiesBuilder = kafkaElementPropertiesBuilder;
        this.rabbitMqElementPropertiesBuilder = rabbitMqElementPropertiesBuilder;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return CamelNames.ASYNC_API_TRIGGER_COMPONENT.equals(type)
               || CamelNames.SERVICE_CALL_COMPONENT.equals(type);
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        return Optional.ofNullable(element.getEnvironment())
                .map(environment -> buildProperties(element, environment))
                .orElse(Collections.emptyMap());
    }

    private Map<String, String> buildProperties(
            ChainElement element,
            ServiceEnvironment environment
    ) {
        Map<String, String> properties = new HashMap<>();
        properties.putAll(buildCommonProperties(element));
        properties.putAll(buildProtocolSpecificProperties(element, environment));
        return properties;
    }

    private Map<String, String> buildProtocolSpecificProperties(ChainElement element, ServiceEnvironment environment) {
        Object protocolType = element.getProperty(CamelNames.OPERATION_PROTOCOL_TYPE_PROP);
        if (CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA.equals(protocolType)) {
            Map<String, String> elementProperties = Optional.ofNullable(environment.getProperties())
                    .map(environmentProperties -> kafkaElementPropertiesBuilder.buildKafkaConnectionProperties(
                            element.getPropertyAsString(OPERATION_PATH_TOPIC),
                            environment.getAddress(),
                            (String) environmentProperties.get(CamelOptions.SECURITY_PROTOCOL),
                            (String) environmentProperties.get(SASL_MECHANISM),
                            (String) environmentProperties.get(CamelOptions.SASL_JAAS_CONFIG),
                            environment.getSourceType() != null ? String.valueOf(environment.getSourceType()) : null
                    ))
                    .orElseThrow(() -> getEnvironmentNotFoundException(element));
            kafkaElementPropertiesBuilder.enrichWithAdditionalProperties(element, elementProperties);
            return elementProperties;
        } else if (CamelNames.OPERATION_PROTOCOL_TYPE_AMQP.equals(protocolType)) {
            Map<String, String> elementProperties = Optional.ofNullable(environment.getProperties())
                    .map(environmentProperties -> rabbitMqElementPropertiesBuilder.buildAmqpConnectionProperties(
                            (String) environmentProperties.get(CamelOptions.SSL),
                            environment.getAddress(),
                            getQueueName(element, ((String) environmentProperties.get(CamelOptions.QUEUES))),
                            element.getPropertyAsString(CamelNames.OPERATION_PATH_EXCHANGE),
                            (String) environmentProperties.get(CamelOptions.USERNAME),
                            (String) environmentProperties.get(CamelOptions.PASSWORD),
                            environment.getSourceType() != null ? String.valueOf(environment.getSourceType()) : null,
                            (String) environmentProperties.get(CamelOptions.VHOST)
                    ))
                    .orElseThrow(() -> getEnvironmentNotFoundException(element));
            rabbitMqElementPropertiesBuilder.enrichWithAdditionalProperties(element, elementProperties);
            return elementProperties;
        } else if (CamelNames.OPERATION_PROTOCOL_TYPE_GRPC.equals(protocolType)) {
            return buildGrpcProperties(element);
        }
        return Collections.emptyMap();
    }

    private static String getQueueName(ChainElement element, String queueNameFromEnv) {
        if (element.getProperties() != null && element.getProperties().containsKey(CamelNames.OPERATION_ASYNC_PROPERTIES)) {
            Map<String, Object> prop = (Map<String, Object>) element.getProperties().get(CamelNames.OPERATION_ASYNC_PROPERTIES);
            if (prop != null && prop.containsKey(CamelOptions.QUEUES)) {
                String queueName = (String) prop.get(CamelOptions.QUEUES);
                return StringUtils.isNotEmpty(queueName) ? queueName : queueNameFromEnv;
            }
        }
        return queueNameFromEnv;
    }

    private static Map<String, String> buildCommonProperties(ChainElement element) {
        return Map.of(CamelNames.OPERATION_PROTOCOL_TYPE_PROP,
                String.valueOf(element.getProperty(CamelNames.OPERATION_PROTOCOL_TYPE_PROP)));
    }

    private static Map<String, String> buildGrpcProperties(ChainElement element) {
        return Map.of(CamelOptions.MODEL_ID, element.getPropertyAsString(CamelOptions.MODEL_ID));
    }

    private static RuntimeException getEnvironmentNotFoundException(ChainElement element) {
        String message = String.format("Can't find active environment for element %s, %s",
                element.getName(), element.getId());
        return new DeploymentProcessingException(message);
    }
}
