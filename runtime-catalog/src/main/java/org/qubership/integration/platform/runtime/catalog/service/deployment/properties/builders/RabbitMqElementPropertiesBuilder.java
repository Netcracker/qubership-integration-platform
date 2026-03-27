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

import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.AdditionalPropertiesBuilder;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.AdditionalPropertiesBuilderProvider;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class RabbitMqElementPropertiesBuilder implements ElementPropertiesBuilder {

    private final Collection<AdditionalPropertiesBuilder> additionalPropertiesBuilders;

    @Autowired
    public RabbitMqElementPropertiesBuilder(AdditionalPropertiesBuilderProvider additionalPropertiesBuilderProvider) {
        this.additionalPropertiesBuilders = additionalPropertiesBuilderProvider.getBuilders(RabbitMqElementPropertiesBuilder.class);
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        return List.of(
                CamelNames.RABBITMQ_TRIGGER_COMPONENT,
                CamelNames.RABBITMQ_SENDER_COMPONENT,
                CamelNames.RABBITMQ_TRIGGER_2_COMPONENT,
                CamelNames.RABBITMQ_SENDER_2_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        Map<String, String> elementProperties = buildAmqpConnectionProperties(
                element.getPropertyAsString(CamelOptions.SSL),
                element.getPropertyAsString(CamelOptions.ADDRESSES),
                element.getPropertyAsString(CamelOptions.QUEUES),
                element.getPropertyAsString(CamelOptions.EXCHANGE),
                element.getPropertyAsString(CamelOptions.USERNAME),
                element.getPropertyAsString(CamelOptions.PASSWORD),
                element.getPropertyAsString(CamelOptions.CONNECTION_SOURCE_TYPE_PROP),
                element.getPropertyAsString(CamelOptions.VHOST)
        );
        enrichWithAdditionalProperties(element, elementProperties);
        return elementProperties;
    }

    public Map<String, String> buildAmqpConnectionProperties(
            String ssl,
            String address,
            String queues,
            String exchange,
            String username,
            String password,
            String sourceType,
            String vHost
    ) {
        Map<String, String> properties = new HashMap<>();
        properties.put(CamelOptions.SSL, ssl);
        properties.put(CamelOptions.ADDRESSES, address);
        properties.put(CamelOptions.QUEUES, queues);
        properties.put(CamelOptions.EXCHANGE, exchange);
        properties.put(CamelOptions.USERNAME, username);
        properties.put(CamelOptions.PASSWORD, password);
        properties.put(CamelOptions.CONNECTION_SOURCE_TYPE_PROP, sourceType);
        properties.put(CamelOptions.VHOST, vHost);
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP);
        return properties;
    }

    public void enrichWithAdditionalProperties(ChainElement element, Map<String, String> elementProperties) {
        additionalPropertiesBuilders.forEach(builder -> elementProperties.putAll(builder.build(element)));
    }
}
