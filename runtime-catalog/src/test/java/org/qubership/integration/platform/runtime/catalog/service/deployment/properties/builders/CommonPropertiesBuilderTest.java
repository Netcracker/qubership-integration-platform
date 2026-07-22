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
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link CommonPropertiesBuilder}: it applies to every element and always emits the element
 * name, type, and original id, adding the wire-tap id only when an input connection comes from an
 * async-split element.
 */
@ExtendWith(MockitoExtension.class)
class CommonPropertiesBuilderTest {

    private final CommonPropertiesBuilder builder = new CommonPropertiesBuilder();

    @Test
    void appliesToEveryElement() {
        assertThat(builder.applicableTo(mock(Element.class))).isTrue();
    }

    @Test
    void buildEmitsTheCoreMetadataWithoutAWireTapForAPlainElement() {
        Element element = mock(Element.class);
        when(element.getName()).thenReturn("Send order");
        when(element.getType()).thenReturn("http-sender");
        when(element.getOriginalId()).thenReturn(Optional.of("orig-1"));
        when(element.getInputConnections()).thenReturn(List.of());

        assertThat(builder.build(element))
                .containsEntry(ConfigurationPropertiesConstants.ELEMENT_NAME, "Send order")
                .containsEntry(ConfigurationPropertiesConstants.ELEMENT_TYPE, "http-sender")
                .containsEntry(ConfigurationPropertiesConstants.ELEMENT_ID, "orig-1")
                .doesNotContainKey(ConfigurationPropertiesConstants.WIRE_TAP_ID);
    }

    @Test
    void buildAddsTheWireTapIdWhenAnInputComesFromAnAsyncSplit() {
        Element source = mock(Element.class);
        when(source.getType()).thenReturn(ConfigurationPropertiesConstants.ASYNC_SPLIT_ELEMENT);
        when(source.getId()).thenReturn("split-1");
        Connection connection = mock(Connection.class);
        when(connection.getFrom()).thenReturn(source);

        Element element = mock(Element.class);
        when(element.getName()).thenReturn("Tap");
        when(element.getType()).thenReturn("http-sender");
        when(element.getOriginalId()).thenReturn(Optional.empty());
        when(element.getInputConnections()).thenReturn(List.of(connection));

        assertThat(builder.build(element))
                .containsEntry(ConfigurationPropertiesConstants.ELEMENT_ID, "")
                .containsEntry(ConfigurationPropertiesConstants.WIRE_TAP_ID, "split-1");
    }

    @Test
    void getWireTapIdJoinsTheInputConnectionSourceIds() {
        Connection first = connectionFrom("a");
        Connection second = connectionFrom("b");
        Element element = mock(Element.class);
        when(element.getInputConnections()).thenReturn(List.of(first, second));

        assertThat(builder.getWireTapId(element)).isEqualTo("a,b");
    }

    @Test
    void isAsyncSplitElementDetectsAnAsyncSplitSource() {
        assertThat(builder.isAsyncSplitElement(elementWithSourceType(ConfigurationPropertiesConstants.ASYNC_SPLIT_ELEMENT)))
                .isTrue();
        assertThat(builder.isAsyncSplitElement(elementWithSourceType("http-trigger"))).isFalse();
    }

    private static Connection connectionFrom(String sourceId) {
        Element source = mock(Element.class);
        when(source.getId()).thenReturn(sourceId);
        Connection connection = mock(Connection.class);
        when(connection.getFrom()).thenReturn(source);
        return connection;
    }

    private static Element elementWithSourceType(String sourceType) {
        Element source = mock(Element.class);
        when(source.getType()).thenReturn(sourceType);
        Connection connection = mock(Connection.class);
        when(connection.getFrom()).thenReturn(source);
        Element element = mock(Element.class);
        when(element.getInputConnections()).thenReturn(List.of(connection));
        return element;
    }
}
