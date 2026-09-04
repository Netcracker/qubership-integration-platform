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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.MaasPropertiesUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link RabbitMqElementPropertiesBuilder}: which element types it handles, the plain
 * connection property map, and the non-MaaS build path that copies the element properties and stamps
 * the AMQP protocol and deployment classifier before delegating environment enrichment.
 */
@ExtendWith(MockitoExtension.class)
class RabbitMqElementPropertiesBuilderTest {

    @Mock
    private MaasPropertiesUtils maasPropertiesUtils;

    @Test
    void applicableToAcceptsRabbitComponentsAndRejectsOthers() {
        RabbitMqElementPropertiesBuilder builder = new RabbitMqElementPropertiesBuilder(maasPropertiesUtils);

        assertThat(builder.applicableTo(elementOfType(CamelNames.RABBITMQ_TRIGGER_COMPONENT))).isTrue();
        assertThat(builder.applicableTo(elementOfType(CamelNames.RABBITMQ_SENDER_COMPONENT))).isTrue();
        assertThat(builder.applicableTo(elementOfType("http-trigger"))).isFalse();
    }

    @Test
    void buildAmqpConnectionPropertiesCopiesEveryFieldAndStampsTheProtocol() {
        RabbitMqElementPropertiesBuilder builder = new RabbitMqElementPropertiesBuilder(maasPropertiesUtils);

        Map<String, String> properties = builder.buildAmqpConnectionProperties(
                "true", "host:5672", "orders", "orders-exchange", "user", "secret", "MANUAL", "/vhost");

        assertThat(properties)
                .containsEntry(CamelOptions.SSL, "true")
                .containsEntry(CamelOptions.ADDRESSES, "host:5672")
                .containsEntry(CamelOptions.QUEUES, "orders")
                .containsEntry(CamelOptions.EXCHANGE, "orders-exchange")
                .containsEntry(CamelOptions.USERNAME, "user")
                .containsEntry(CamelOptions.PASSWORD, "secret")
                .containsEntry(CamelOptions.CONNECTION_SOURCE_TYPE_PROP, "MANUAL")
                .containsEntry(CamelOptions.VHOST, "/vhost")
                .containsEntry(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP);
    }

    @Test
    void buildCopiesElementPropertiesAndEnrichesEnvironmentForAPlainTrigger() {
        Element element = elementOfType(CamelNames.RABBITMQ_TRIGGER_COMPONENT);
        when(element.getOriginalId()).thenReturn(Optional.of("orig-1"));
        Map<String, Object> elementProperties = new HashMap<>();
        elementProperties.put(CamelOptions.ADDRESSES, "host:5672");
        elementProperties.put(CamelOptions.QUEUES, "orders");
        elementProperties.put(CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP, "vhost-classifier");
        when(element.getProperties()).thenReturn(elementProperties);

        RabbitMqElementPropertiesBuilder builder = new RabbitMqElementPropertiesBuilder(maasPropertiesUtils);
        Map<String, String> result = builder.build(element);

        assertThat(result)
                .containsEntry(CamelOptions.ADDRESSES, "host:5672")
                .containsEntry(CamelOptions.QUEUES, "orders")
                .containsEntry(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP)
                .containsEntry(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP, "vhost-classifier");
        verify(maasPropertiesUtils).enrichWithMaasEnvProperties(eq(element), any());
    }

    private Element elementOfType(String type) {
        Element element = mock(Element.class);
        when(element.getType()).thenReturn(type);
        return element;
    }
}
