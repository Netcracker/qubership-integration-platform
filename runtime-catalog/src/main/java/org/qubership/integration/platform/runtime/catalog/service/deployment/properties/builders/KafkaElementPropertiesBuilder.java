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

import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.AdditionalPropertiesBuilder;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.AdditionalPropertiesBuilderProvider;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class KafkaElementPropertiesBuilder implements ElementPropertiesBuilder {

    private final Collection<AdditionalPropertiesBuilder> additionalPropertiesBuilders;

    @Autowired
    public KafkaElementPropertiesBuilder(AdditionalPropertiesBuilderProvider additionalPropertiesBuilderProvider) {
        this.additionalPropertiesBuilders = additionalPropertiesBuilderProvider.getBuilders(KafkaElementPropertiesBuilder.class);
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        return Set.of(
                CamelNames.KAFKA_TRIGGER_COMPONENT,
                CamelNames.KAFKA_SENDER_COMPONENT,
                CamelNames.KAFKA_TRIGGER_2_COMPONENT,
                CamelNames.KAFKA_SENDER_2_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        Map<String, String> elementProperties = buildKafkaConnectionProperties(
                element.getPropertyAsString(CamelOptions.TOPICS),
                element.getPropertyAsString(CamelOptions.BROKERS),
                element.getPropertyAsString(CamelOptions.SECURITY_PROTOCOL),
                element.getPropertyAsString(CamelOptions.SASL_MECHANISM),
                element.getPropertyAsString(CamelOptions.SASL_JAAS_CONFIG),
                element.getPropertyAsString(CamelOptions.CONNECTION_SOURCE_TYPE_PROP)
        );
        if (CamelNames.KAFKA_TRIGGER_2_COMPONENT.equals(element.getType())) {
            elementProperties.put(CamelOptions.GROUP_ID, element.getPropertyAsString(CamelOptions.GROUP_ID));
        }
        enrichWithAdditionalProperties(element, elementProperties);
        return elementProperties;
    }

    public Map<String, String> buildKafkaConnectionProperties(
            String topics,
            String brokers,
            String securityProtocol,
            String saslMechanism,
            String saslJaasConfig,
            String sourceType
    ) {
        Map<String, String> properties = new HashMap<>();
        properties.put(CamelOptions.TOPICS, topics);
        properties.put(CamelOptions.BROKERS, brokers);
        properties.put(CamelOptions.SECURITY_PROTOCOL, securityProtocol);
        properties.put(CamelOptions.SASL_MECHANISM, saslMechanism);
        properties.put(CamelOptions.SASL_JAAS_CONFIG, saslJaasConfig);
        properties.put(CamelOptions.CONNECTION_SOURCE_TYPE_PROP, sourceType);
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        return properties;
    }

    public void enrichWithAdditionalProperties(ChainElement element, Map<String, String> elementProperties) {
        additionalPropertiesBuilders.forEach(builder -> elementProperties.putAll(builder.build(element)));
    }
}
