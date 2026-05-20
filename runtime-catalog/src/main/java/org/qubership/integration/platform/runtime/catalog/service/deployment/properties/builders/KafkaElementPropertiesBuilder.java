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
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.constant.ConnectionSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.MaasPropertiesUtils;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.util.MaasUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.MAAS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.OPERATION_PATH_TOPIC;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.BROKERS;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.CONNECTION_SOURCE_TYPE_PROP;

@Component
public class KafkaElementPropertiesBuilder implements ElementPropertiesBuilder {

    private final MaasPropertiesUtils maasPropertiesUtils;

    @Autowired
    public KafkaElementPropertiesBuilder(MaasPropertiesUtils maasPropertiesUtils) {
        this.maasPropertiesUtils = maasPropertiesUtils;
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
        enrichWithMaasProperties(element, elementProperties);
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

    public void enrichWithMaasProperties(ChainElement element, Map<String, String> elementProperties) {
        String elementOriginalId = element.getOriginalId();

        if (isMaasKafkaTriggerOrSender(element)) {
            elementProperties.put(CamelOptions.TOPICS, MaasUtils.getMaasParamPlaceholder(elementOriginalId, CamelOptions.TOPICS));
            elementProperties.put(BROKERS, MaasUtils.getMaasParamPlaceholder(elementOriginalId, BROKERS));
            elementProperties.put(CamelOptions.SECURITY_PROTOCOL, MaasUtils.getMaasParamPlaceholder(elementOriginalId, CamelOptions.SECURITY_PROTOCOL));
            elementProperties.put(CamelOptions.SASL_MECHANISM, MaasUtils.getMaasParamPlaceholder(elementOriginalId, CamelOptions.SASL_MECHANISM));
            elementProperties.put(CamelOptions.SASL_JAAS_CONFIG, MaasUtils.getMaasParamPlaceholder(elementOriginalId, CamelOptions.SASL_JAAS_CONFIG));
            elementProperties.put(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP, element.getPropertyAsString(CamelOptions.MAAS_TOPICS_CLASSIFIER_NAME_PROP));
            maasPropertiesUtils.enrichWithMaasEnvProperties(element, elementProperties);
            return;
        }
        if (isAsyncElement(element)) {
            if (isMaasEnvParameterEnabled(element)) {
                elementProperties.put(CamelOptions.TOPICS, MaasUtils.getMaasParamPlaceholder(elementOriginalId, OPERATION_PATH_TOPIC));
                elementProperties.put(BROKERS, MaasUtils.getMaasParamPlaceholder(elementOriginalId, BROKERS));
                elementProperties.put(CamelOptions.SECURITY_PROTOCOL, MaasUtils.getMaasParamPlaceholder(elementOriginalId, CamelOptions.SECURITY_PROTOCOL));
                elementProperties.put(CamelOptions.SASL_MECHANISM, MaasUtils.getMaasParamPlaceholder(elementOriginalId, CamelOptions.SASL_MECHANISM));
                elementProperties.put(CamelOptions.SASL_JAAS_CONFIG, MaasUtils.getMaasParamPlaceholder(elementOriginalId, CamelOptions.SASL_JAAS_CONFIG));
            }

            elementProperties.put(
                    CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP,
                    (String) ElementUtils.extractOperationAsyncProperties(element.getProperties()).get(CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP)
            );
            elementProperties.put(
                    CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP,
                    (String) ElementUtils.extractOperationAsyncProperties(element.getProperties()).get(MAAS_CLASSIFIER_NAME_PROP)
            );
            elementProperties.put(
                    MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP,
                    (String) ElementUtils.extractOperationAsyncProperties(element.getProperties()).get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME)
            );
            elementProperties.put(
                    MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP,
                    (String) ElementUtils.extractOperationAsyncProperties(element.getProperties()).get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME)
            );
            return;
        }

        elementProperties.put(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP, element.getPropertyAsString(CamelOptions.MAAS_TOPICS_CLASSIFIER_NAME_PROP));
        maasPropertiesUtils.enrichWithMaasEnvProperties(element, elementProperties);
    }

    private boolean isMaasKafkaTriggerOrSender(ChainElement element) {
        String elementType = element.getType();
        return (
                StringUtils.equalsIgnoreCase(elementType, CamelNames.KAFKA_SENDER_2_COMPONENT)
                        || StringUtils.equalsIgnoreCase(elementType, CamelNames.KAFKA_TRIGGER_2_COMPONENT)
        )
                && ConnectionSourceType.MAAS.toString().equalsIgnoreCase(element.getPropertyAsString(CONNECTION_SOURCE_TYPE_PROP));
    }

    private boolean isAsyncElement(ChainElement element) {
        String type = element.getType();
        return CamelNames.ASYNC_API_TRIGGER_COMPONENT.equals(type)
                || CamelNames.SERVICE_CALL_COMPONENT.equals(type);
    }

    private boolean isMaasEnvParameterEnabled(ChainElement element) {
        return Optional.ofNullable(element.getEnvironment())
                .map(environment -> environment.getSourceType() == EnvironmentSourceType.MAAS_BY_CLASSIFIER)
                .orElse(false);
    }
}
