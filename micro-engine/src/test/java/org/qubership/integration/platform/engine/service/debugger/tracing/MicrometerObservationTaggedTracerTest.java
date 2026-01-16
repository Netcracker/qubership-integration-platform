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

package org.qubership.integration.platform.engine.service.debugger.tracing;

import org.apache.camel.Exchange;
import org.apache.camel.tracing.SpanAdapter;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MicrometerObservationTaggedTracerTest {

    @Test
    void shouldInsertCustomTagsIntoSpanWhenTagsPresent() {
        Exchange exchange = MockExchanges.basic();
        SpanAdapter span = mock(SpanAdapter.class);

        Map<String, Object> properties = new HashMap<>();
        Map<String, String> tags = new HashMap<>();
        tags.put("env", "dev");
        tags.put("region", "eu-west");
        properties.put(
                org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.TRACING_CUSTOM_TAGS,
                tags
        );
        when(exchange.getProperties()).thenReturn(properties);

        SpanAdapter result = MicrometerObservationTaggedTracer.insertCustomTagsToSpan(exchange, span);

        verify(span).setTag("env", "dev");
        verify(span).setTag("region", "eu-west");
        verifyNoMoreInteractions(span);
        assertSame(span, result);
    }

    @Test
    void shouldNotSetAnyTagsWhenPropertyMissing() {
        Exchange exchange =  MockExchanges.basic();
        SpanAdapter span = mock(SpanAdapter.class);

        when(exchange.getProperties()).thenReturn(new HashMap<>());

        SpanAdapter result = MicrometerObservationTaggedTracer.insertCustomTagsToSpan(exchange, span);

        verifyNoInteractions(span);
        assertSame(span, result);
    }

    @Test
    void shouldNotSetAnyTagsWhenPropertyIsEmptyMap() {
        Exchange exchange =  MockExchanges.basic();
        SpanAdapter span = mock(SpanAdapter.class);

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.TRACING_CUSTOM_TAGS,
                Map.of()
        );
        when(exchange.getProperties()).thenReturn(properties);

        SpanAdapter result = MicrometerObservationTaggedTracer.insertCustomTagsToSpan(exchange, span);

        verifyNoInteractions(span);
        assertSame(span, result);
    }
}
