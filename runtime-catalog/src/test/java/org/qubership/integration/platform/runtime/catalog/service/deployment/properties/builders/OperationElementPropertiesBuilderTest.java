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
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link OperationElementPropertiesBuilder}: which element types it handles, the empty result
 * without an environment, the gRPC path, delegation to the Kafka connection builder, and the missing
 * environment-properties failure.
 */
@ExtendWith(MockitoExtension.class)
class OperationElementPropertiesBuilderTest {

    @Mock
    private KafkaElementPropertiesBuilder kafkaElementPropertiesBuilder;
    @Mock
    private RabbitMqElementPropertiesBuilder rabbitMqElementPropertiesBuilder;

    private OperationElementPropertiesBuilder builder() {
        return new OperationElementPropertiesBuilder(kafkaElementPropertiesBuilder, rabbitMqElementPropertiesBuilder);
    }

    @Test
    void applicableToAcceptsAsyncTriggerAndServiceCall() {
        assertThat(builder().applicableTo(elementOfType(CamelNames.ASYNC_API_TRIGGER_COMPONENT))).isTrue();
        assertThat(builder().applicableTo(elementOfType(CamelNames.SERVICE_CALL_COMPONENT))).isTrue();
        assertThat(builder().applicableTo(elementOfType("http-sender"))).isFalse();
    }

    @Test
    void buildReturnsEmptyWhenTheElementHasNoEnvironment() {
        Element element = mock(Element.class);
        when(element.getEnvironment()).thenReturn(Optional.empty());

        assertThat(builder().build(element)).isEmpty();
    }

    @Test
    void buildForGrpcCarriesTheProtocolAndModelId() {
        Element element = mock(Element.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_GRPC);
        properties.put(CamelOptions.MODEL_ID, "model-1");
        when(element.getProperties()).thenReturn(properties);
        when(element.getEnvironment()).thenReturn(Optional.of(mock(ServiceEnvironment.class)));

        assertThat(builder().build(element))
                .containsEntry(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_GRPC)
                .containsEntry(CamelOptions.MODEL_ID, "model-1");
    }

    @Test
    void buildForKafkaDelegatesToTheKafkaConnectionBuilderAndEnrichesWithMaas() {
        Element element = mock(Element.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        when(element.getProperties()).thenReturn(properties);
        ServiceEnvironment environment = mock(ServiceEnvironment.class);
        when(environment.getProperties()).thenReturn(Map.of(CamelOptions.SECURITY_PROTOCOL, "SASL_SSL"));
        when(environment.getAddress()).thenReturn("broker:9092");
        when(element.getEnvironment()).thenReturn(Optional.of(environment));
        Map<String, String> kafkaProperties = new HashMap<>();
        kafkaProperties.put(CamelOptions.TOPICS, "orders");
        when(kafkaElementPropertiesBuilder.buildKafkaConnectionProperties(any(), any(), any(), any(), any(), any()))
                .thenReturn(kafkaProperties);

        Map<String, String> result = builder().build(element);

        assertThat(result)
                .containsEntry(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA)
                .containsEntry(CamelOptions.TOPICS, "orders");
        verify(kafkaElementPropertiesBuilder).enrichWithMaasProperties(eq(element), any());
    }

    @Test
    void buildForKafkaFailsWhenTheEnvironmentHasNoProperties() {
        Element element = mock(Element.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        when(element.getProperties()).thenReturn(properties);
        ServiceEnvironment environment = mock(ServiceEnvironment.class);
        when(environment.getProperties()).thenReturn(null);
        when(element.getEnvironment()).thenReturn(Optional.of(environment));

        OperationElementPropertiesBuilder builder = builder();
        assertThatThrownBy(() -> builder.build(element))
                .isInstanceOf(DeploymentProcessingException.class);
    }

    private Element elementOfType(String type) {
        Element element = mock(Element.class);
        when(element.getType()).thenReturn(type);
        return element;
    }
}
