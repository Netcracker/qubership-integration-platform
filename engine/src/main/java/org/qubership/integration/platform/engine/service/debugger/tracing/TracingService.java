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

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.TRACING_CUSTOM_TAGS;

import org.qubership.integration.platform.engine.configuration.TracingConfiguration;
import org.qubership.integration.platform.engine.logging.constants.ContextHeaders;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TracingService {
    public static final String X_REQUEST_ID = "X-Request-Id";

    private final TracingConfiguration tracingConfiguration;

    @Autowired
    public TracingService(TracingConfiguration tracingConfiguration) {
        this.tracingConfiguration = tracingConfiguration;
    }

    public boolean isTracingEnabled() {
        return tracingConfiguration.isTracingEnabled();
    }

    public void addElementTracingTags(Exchange exchange, String nodeId,
        CamelDebuggerProperties dbgProperties) {
        if (dbgProperties.containsElementProperty(nodeId)) {
            Map<String, String> customTags = new HashMap<>();
            customTags.put(ChainProperties.ELEMENT_NAME,
                dbgProperties.getElementProperty(nodeId).get(ChainProperties.ELEMENT_NAME));
            customTags.put(ChainProperties.ELEMENT_TYPE,
                dbgProperties.getElementProperty(nodeId).get(ChainProperties.ELEMENT_TYPE));
            setXRequestTag(customTags);
            addTracingTagsToProperties(exchange, customTags);
            SpanAdapter spanAdapter = ActiveSpanManager.getSpan(exchange);
            if (spanAdapter != null) {
                MicrometerObservationTaggedTracer.insertCustomTagsToSpan(exchange, spanAdapter);
            }
        }
    }

    public void addChainTracingTags(Exchange exchange, CamelDebuggerProperties dbgProperties) {
        Map<String, String> customTags = new HashMap<>();
        customTags.put(Properties.SESSION_ID,
            exchange.getProperty(Properties.SESSION_ID).toString());
        customTags.put(ChainProperties.CHAIN_ID, dbgProperties.getDeploymentInfo().getChainId());
        customTags.put(ChainProperties.CHAIN_NAME, dbgProperties.getDeploymentInfo().getChainName());
        setXRequestTag(customTags);

        addTracingTagsToProperties(exchange, customTags);
    }

    private static void setXRequestTag(Map<String, String> customTags) {
        String xRequestId = MDC.get(ContextHeaders.REQUEST_ID_HEADER);
        if (!StringUtils.isEmpty(xRequestId)) {
            customTags.put(X_REQUEST_ID, xRequestId);
        }
    }

    private void addTracingTagsToProperties(Exchange exchange, Map<String, String> customTags) {
        Map<String, String> tags = (Map<String, String>) exchange
            .getProperties()
            .getOrDefault(TRACING_CUSTOM_TAGS, new HashMap<>());
        tags.putAll(customTags);
        exchange.setProperty(TRACING_CUSTOM_TAGS, tags);
    }
}
