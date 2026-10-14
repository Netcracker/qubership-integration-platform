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
 * Covers {@link PubSubElementPropertiesBuilder}: the handled element types, the plain connection
 * property map, and the build path that reads the project, destination, and service-account-key
 * values from the element.
 */
@ExtendWith(MockitoExtension.class)
class PubSubElementPropertiesBuilderTest {

    private final PubSubElementPropertiesBuilder builder = new PubSubElementPropertiesBuilder();

    @Test
    void applicableToAcceptsPubSubComponentsAndRejectsOthers() {
        assertThat(builder.applicableTo(elementOfType(CamelNames.PUBSUB_TRIGGER_COMPONENT))).isTrue();
        assertThat(builder.applicableTo(elementOfType(CamelNames.PUBSUB_SENDER_COMPONENT))).isTrue();
        assertThat(builder.applicableTo(elementOfType("kafka-sender"))).isFalse();
    }

    @Test
    void buildPubSubConnectionPropertiesCopiesEveryField() {
        Map<String, String> properties = PubSubElementPropertiesBuilder.buildPubSubConnectionProperties(
                "project-1", "orders-topic", "service-key");

        assertThat(properties)
                .containsEntry(CamelOptions.PROJECT_ID, "project-1")
                .containsEntry(CamelOptions.DESTINATION_NAME, "orders-topic")
                .containsEntry(CamelOptions.SERVICE_ACCOUNT_KEY, "service-key");
    }

    @Test
    void buildReadsTheConnectionFieldsFromTheElement() {
        Element element = mock(Element.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelOptions.PROJECT_ID, "project-1");
        properties.put(CamelOptions.DESTINATION_NAME, "orders-topic");
        properties.put(CamelOptions.SERVICE_ACCOUNT_KEY, "service-key");
        when(element.getProperties()).thenReturn(properties);

        assertThat(builder.build(element))
                .containsEntry(CamelOptions.PROJECT_ID, "project-1")
                .containsEntry(CamelOptions.DESTINATION_NAME, "orders-topic")
                .containsEntry(CamelOptions.SERVICE_ACCOUNT_KEY, "service-key");
    }

    private Element elementOfType(String type) {
        Element element = mock(Element.class);
        when(element.getType()).thenReturn(type);
        return element;
    }
}
