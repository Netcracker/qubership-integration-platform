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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.configuration.TracingConfiguration;
import org.qubership.integration.platform.engine.logging.ContextHeaders;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.util.ExchangeUtil;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.TRACING_CUSTOM_TAGS;

@Slf4j
@ApplicationScoped
public class TracingService {
    public static final String X_REQUEST_ID = "X-Request-Id";

    private final TracingConfiguration tracingConfiguration;

    @Inject
    public TracingService(TracingConfiguration tracingConfiguration) {
        this.tracingConfiguration = tracingConfiguration;
    }

    public boolean isTracingEnabled() {
        return tracingConfiguration.isTracingEnabled();
    }

    public void addElementTracingTags(Exchange exchange, ElementInfo elementInfo) {
        Map<String, String> customTags = new HashMap<>();
        customTags.put(ChainProperties.ELEMENT_NAME, elementInfo.getName());
        customTags.put(ChainProperties.ELEMENT_TYPE, elementInfo.getType());
        setXRequestTag(customTags);
        addTracingTagsToProperties(exchange, customTags);
        SpanAdapter spanAdapter = ActiveSpanManager.getSpan(exchange);
        if (spanAdapter != null) {
            MicrometerObservationTaggedTracer.insertCustomTagsToSpan(exchange, spanAdapter);
        }
    }

    public void addChainTracingTags(Exchange exchange) {
        Map<String, String> customTags = new HashMap<>();
        customTags.put(ChainProperties.SESSION_ID, ExchangeUtil.getSessionId(exchange));
        ChainInfo chainInfo = MetadataUtil.getBean(exchange, DeploymentInfo.class).getChain();
        customTags.put(ChainProperties.CHAIN_ID, chainInfo.getId());
        customTags.put(ChainProperties.CHAIN_NAME, chainInfo.getName());
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
