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

import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.AdditionalPropertiesBuilder;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.AdditionalPropertiesBuilderProvider;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.util.ElementUtils.getPropertyAsString;


@Component
public class RabbitMqElementPropertiesBuilder implements ElementPropertiesBuilder {

    private final Collection<AdditionalPropertiesBuilder> additionalPropertiesBuilders;

    @Autowired
    public RabbitMqElementPropertiesBuilder(AdditionalPropertiesBuilderProvider additionalPropertiesBuilderProvider) {
        this.additionalPropertiesBuilders = additionalPropertiesBuilderProvider.getBuilders(RabbitMqElementPropertiesBuilder.class);
    }

    @Override
    public boolean applicableTo(Element element) {
        return List.of(
                CamelNames.RABBITMQ_TRIGGER_COMPONENT,
                CamelNames.RABBITMQ_SENDER_COMPONENT,
                CamelNames.RABBITMQ_TRIGGER_2_COMPONENT,
                CamelNames.RABBITMQ_SENDER_2_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(Element element) {
        Map<String, String> elementProperties = buildAmqpConnectionProperties(
                getPropertyAsString(element, CamelOptions.SSL),
                getPropertyAsString(element, CamelOptions.ADDRESSES),
                getPropertyAsString(element, CamelOptions.QUEUES),
                getPropertyAsString(element, CamelOptions.EXCHANGE),
                getPropertyAsString(element, CamelOptions.USERNAME),
                getPropertyAsString(element, CamelOptions.PASSWORD),
                getPropertyAsString(element, CamelOptions.CONNECTION_SOURCE_TYPE_PROP),
                getPropertyAsString(element, CamelOptions.VHOST)
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

    public void enrichWithAdditionalProperties(Element element, Map<String, String> elementProperties) {
        additionalPropertiesBuilders.forEach(builder -> elementProperties.putAll(builder.build(element)));
    }
}
