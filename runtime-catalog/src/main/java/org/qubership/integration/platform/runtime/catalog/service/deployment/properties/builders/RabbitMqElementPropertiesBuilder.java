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
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.io.util.MaasUtils;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.library.constants.ConnectionSourceType;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.MaasPropertiesUtils;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.library.constants.CamelNames.MAAS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.library.constants.CamelOptions.*;
import static org.qubership.integration.platform.util.ElementUtils.getPropertyAsString;

@Component
public class RabbitMqElementPropertiesBuilder implements ElementPropertiesBuilder {

    private final MaasPropertiesUtils maasPropertiesUtils;

    @Autowired
    public RabbitMqElementPropertiesBuilder(MaasPropertiesUtils maasPropertiesUtils) {
        this.maasPropertiesUtils = maasPropertiesUtils;
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
                getPropertyAsString(element, SSL),
                getPropertyAsString(element, ADDRESSES),
                getPropertyAsString(element, CamelOptions.QUEUES),
                getPropertyAsString(element, CamelOptions.EXCHANGE),
                getPropertyAsString(element, USERNAME),
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
        properties.put(SSL, ssl);
        properties.put(ADDRESSES, address);
        properties.put(CamelOptions.QUEUES, queues);
        properties.put(CamelOptions.EXCHANGE, exchange);
        properties.put(USERNAME, username);
        properties.put(CamelOptions.PASSWORD, password);
        properties.put(CamelOptions.CONNECTION_SOURCE_TYPE_PROP, sourceType);
        properties.put(CamelOptions.VHOST, vHost);
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP);
        return properties;
    }

    public void enrichWithAdditionalProperties(Element element, Map<String, String> elementProperties) {
        String elementOriginalId = element.getOriginalId().orElse("");

        if (isMaasRabbitTriggerOrSender(element)) {
            elementProperties.put(SSL, MaasUtils.getMaasParamPlaceholder(elementOriginalId, SSL));
            elementProperties.put(ADDRESSES, MaasUtils.getMaasParamPlaceholder(elementOriginalId, ADDRESSES));
            elementProperties.put(USERNAME, MaasUtils.getMaasParamPlaceholder(elementOriginalId, USERNAME));
            elementProperties.put(CamelOptions.PASSWORD, MaasUtils.getMaasParamPlaceholder(elementOriginalId, PASSWORD));
            elementProperties.put(CamelOptions.VHOST, MaasUtils.getMaasParamPlaceholder(elementOriginalId, VHOST));
            elementProperties.put(
                    CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP,
                    ElementUtils.getPropertyAsString(element, CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP)
            );
            maasPropertiesUtils.enrichWithMaasEnvProperties(element, elementProperties);
            return;
        }
        if (isAsyncElement(element)) {
            if (isMaasEnvParameterEnabled(element)) {
                elementProperties.put(SSL, MaasUtils.getMaasParamPlaceholder(elementOriginalId, SSL));
                elementProperties.put(ADDRESSES, MaasUtils.getMaasParamPlaceholder(elementOriginalId, ADDRESSES));
                elementProperties.put(USERNAME, MaasUtils.getMaasParamPlaceholder(elementOriginalId, USERNAME));
                elementProperties.put(CamelOptions.PASSWORD, MaasUtils.getMaasParamPlaceholder(elementOriginalId, PASSWORD));
                elementProperties.put(VHOST, MaasUtils.getMaasParamPlaceholder(elementOriginalId, VHOST));
            }
            elementProperties.put(
                    MAAS_DEPLOYMENT_CLASSIFIER_PROP,
                    (String) ElementUtils.extractOperationAsyncProperties(element.getProperties()).get(MAAS_CLASSIFIER_NAME_PROP)
            );
            elementProperties.put(
                    MAAS_CLASSIFIER_NAMESPACE_PROP,
                    (String) ElementUtils.extractOperationAsyncProperties(element.getProperties()).get(CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP)
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

        elementProperties.put(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP, ElementUtils.getPropertyAsString(element, CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP));
        maasPropertiesUtils.enrichWithMaasEnvProperties(element, elementProperties);
    }

    private boolean isMaasRabbitTriggerOrSender(Element element) {
        String elementType = element.getType();
        return (
                StringUtils.equalsIgnoreCase(elementType, CamelNames.RABBITMQ_SENDER_2_COMPONENT)
                        || StringUtils.equalsIgnoreCase(elementType, CamelNames.RABBITMQ_TRIGGER_2_COMPONENT)
        )
                && ConnectionSourceType.MAAS.toString().equalsIgnoreCase(ElementUtils.getPropertyAsString(element, CONNECTION_SOURCE_TYPE_PROP));
    }

    private boolean isAsyncElement(Element element) {
        String type = element.getType();
        return CamelNames.ASYNC_API_TRIGGER_COMPONENT.equals(type)
                || CamelNames.SERVICE_CALL_COMPONENT.equals(type);
    }

    private boolean isMaasEnvParameterEnabled(Element element) {
        return element.getEnvironment()
                .map(environment -> environment.getSourceType() == EnvironmentSourceType.MAAS_BY_CLASSIFIER)
                .orElse(false);
    }
}
