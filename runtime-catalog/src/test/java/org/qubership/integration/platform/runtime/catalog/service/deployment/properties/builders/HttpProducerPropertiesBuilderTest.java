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
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link HttpProducerPropertiesBuilder}: which element types it handles, the HTTP-sender path
 * that copies the URI into the exchange path and defaults the reuse-connection flag, and the
 * service-call path that keeps the operation path only for HTTP/GraphQL protocols.
 */
@ExtendWith(MockitoExtension.class)
class HttpProducerPropertiesBuilderTest {

    private final HttpProducerPropertiesBuilder builder = new HttpProducerPropertiesBuilder();

    @Test
    void applicableToAcceptsHttpProducersAndRejectsOthers() {
        assertThat(builder.applicableTo(elementOfType(CamelNames.HTTP_SENDER_COMPONENT))).isTrue();
        assertThat(builder.applicableTo(elementOfType(CamelNames.GRAPHQL_SENDER_COMPONENT))).isTrue();
        assertThat(builder.applicableTo(elementOfType(CamelNames.SERVICE_CALL_COMPONENT))).isTrue();
        assertThat(builder.applicableTo(elementOfType("kafka-sender"))).isFalse();
    }

    @Test
    void buildForHttpSenderCopiesTheUriAndKeepsTheReuseFlag() {
        Element element = elementOfType(CamelNames.HTTP_SENDER_COMPONENT);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelOptions.URI, "http://service/api");
        properties.put(CamelNames.REUSE_ESTABLISHED_CONN, "false");
        when(element.getProperties()).thenReturn(properties);

        assertThat(builder.build(element))
                .containsEntry(CamelNames.OPERATION_PATH_EXCHANGE, "http://service/api")
                .containsEntry(CamelNames.REUSE_ESTABLISHED_CONN, "false");
    }

    @Test
    void buildForHttpSenderDefaultsTheReuseFlagWhenMissing() {
        Element element = elementOfType(CamelNames.HTTP_SENDER_COMPONENT);
        when(element.getProperties()).thenReturn(new HashMap<>());

        Map<String, String> result = builder.build(element);

        assertThat(result).containsEntry(CamelNames.REUSE_ESTABLISHED_CONN, HttpProducerPropertiesBuilder.REUSE_CONN_DEFAULT_VALUE);
        assertThat(result).doesNotContainKey(CamelNames.OPERATION_PATH_EXCHANGE);
    }

    @Test
    void buildForServiceCallKeepsTheOperationPathAndReuseFlagForHttpProtocol() {
        Element element = elementOfType(CamelNames.SERVICE_CALL_COMPONENT);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_HTTP);
        properties.put(CamelOptions.OPERATION_PATH, "/api/orders");
        properties.put(CamelNames.SERVICE_CALL_ADDITIONAL_PARAMETERS,
                Map.of(CamelNames.REUSE_ESTABLISHED_CONN, "false"));
        when(element.getProperties()).thenReturn(properties);

        assertThat(builder.build(element))
                .containsEntry(CamelOptions.OPERATION_PATH, "/api/orders")
                .containsEntry(CamelNames.REUSE_ESTABLISHED_CONN, "false");
    }

    @Test
    void buildForServiceCallReturnsEmptyForANonHttpProtocol() {
        Element element = elementOfType(CamelNames.SERVICE_CALL_COMPONENT);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        when(element.getProperties()).thenReturn(properties);

        assertThat(builder.build(element)).isEmpty();
    }

    private Element elementOfType(String type) {
        Element element = mock(Element.class);
        when(element.getType()).thenReturn(type);
        return element;
    }
}
