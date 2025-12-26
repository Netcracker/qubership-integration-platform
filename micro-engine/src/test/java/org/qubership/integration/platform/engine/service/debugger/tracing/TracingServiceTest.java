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
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.engine.configuration.TracingConfiguration;
import org.qubership.integration.platform.engine.logging.constants.ContextHeaders;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TracingServiceTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void isTracingEnabledShouldDelegateToConfig() {
        TracingConfiguration cfg = mock(TracingConfiguration.class);
        when(cfg.isTracingEnabled()).thenReturn(true);

        TracingService svc = new TracingService(cfg);
        assertTrue(svc.isTracingEnabled());
    }

    @Test
    void addChainTracingTagsShouldPutTagsIntoExchangePropertiesAndXRequestId() {
        TracingConfiguration cfg = mock(TracingConfiguration.class);
        TracingService svc = new TracingService(cfg);

        Exchange ex = new DefaultExchange(new DefaultCamelContext());
        ex.setProperty(Properties.SESSION_ID, "S-1");

        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
        var depInfo = mock(DeploymentInfo.class);
        when(dbg.getDeploymentInfo()).thenReturn(depInfo);
        when(depInfo.getChainId()).thenReturn("C-1");
        when(depInfo.getChainName()).thenReturn("CN");

        MDC.put(ContextHeaders.REQUEST_ID_HEADER, "REQ-1");

        svc.addChainTracingTags(ex, dbg);

        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) ex.getProperties()
                .get(Properties.TRACING_CUSTOM_TAGS);

        assertEquals("S-1", tags.get(Properties.SESSION_ID));
        assertEquals("C-1", tags.get(ChainProperties.CHAIN_ID));
        assertEquals("CN", tags.get(ChainProperties.CHAIN_NAME));
        assertEquals("REQ-1", tags.get(TracingService.X_REQUEST_ID));
    }

    @Test
    void addElementTracingTagsShouldDoNothingWhenNoElementProperty() {
        TracingConfiguration cfg = mock(TracingConfiguration.class);
        TracingService svc = new TracingService(cfg);

        Exchange ex = new DefaultExchange(new DefaultCamelContext());
        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
        when(dbg.containsElementProperty("n1")).thenReturn(false);

        svc.addElementTracingTags(ex, "n1", dbg);

        assertNull(ex.getProperties().get(
                Properties.TRACING_CUSTOM_TAGS));
    }

    @Test
    void addElementTracingTagsShouldInsertCustomTagsToSpanWhenSpanPresent() {
        TracingConfiguration cfg = mock(TracingConfiguration.class);
        TracingService svc = new TracingService(cfg);

        Exchange ex = new DefaultExchange(new DefaultCamelContext());
        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
        when(dbg.containsElementProperty("n1")).thenReturn(true);
        when(dbg.getElementProperty("n1")).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "E",
                ChainProperties.ELEMENT_TYPE, "HTTP_SENDER"
        ));

        SpanAdapter span = mock(SpanAdapter.class);

        try (MockedStatic<ActiveSpanManager> spanMgr = mockStatic(ActiveSpanManager.class);
             MockedStatic<MicrometerObservationTaggedTracer> tagged = mockStatic(MicrometerObservationTaggedTracer.class)) {

            spanMgr.when(() -> ActiveSpanManager.getSpan(ex)).thenReturn(span);

            svc.addElementTracingTags(ex, "n1", dbg);

            tagged.verify(() -> MicrometerObservationTaggedTracer.insertCustomTagsToSpan(ex, span), times(1));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) ex.getProperties()
                .get(Properties.TRACING_CUSTOM_TAGS);

        assertEquals("E", tags.get(ChainProperties.ELEMENT_NAME));
        assertEquals("HTTP_SENDER", tags.get(ChainProperties.ELEMENT_TYPE));
    }
}
