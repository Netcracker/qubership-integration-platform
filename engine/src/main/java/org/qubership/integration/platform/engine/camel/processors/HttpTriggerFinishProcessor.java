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

package org.qubership.integration.platform.engine.camel.processors;

import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
public class HttpTriggerFinishProcessor implements Processor {

    private final CamelDebuggerPropertiesService propertiesService;
    private final PayloadExtractor payloadExtractor;
    private final ChainLogger chainLogger;
    private final MetricsService metricsService;

    @Autowired
    public HttpTriggerFinishProcessor(CamelDebuggerPropertiesService propertiesService,
                                      PayloadExtractor payloadExtractor,
                                      ChainLogger chainLogger,
                                      MetricsService metricsService) {
        this.propertiesService = propertiesService;
        this.payloadExtractor = payloadExtractor;
        this.chainLogger = chainLogger;
        this.metricsService = metricsService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Boolean sessionFailed = exchange.getProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false, Boolean.class);
        Exception exception = sessionFailed ? exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class) : null;
        CamelDebuggerProperties dbgProperties = getCamelDebuggerProperties(exchange);

        logHttpTriggerRequestFinished(exchange, dbgProperties, exception);
        logMetrics(exchange, dbgProperties, exception);
    }

    private void logMetrics(Exchange exchange, CamelDebuggerProperties dbgProperties, Exception exception) {
        try {
            int responseCode = PayloadExtractor.getServletResponseCode(exchange, exception);
            metricsService.processHttpResponseCode(dbgProperties, String.valueOf(responseCode));
            metricsService.processHttpTriggerPayloadSize(exchange, dbgProperties);
        } catch (Exception e) {
            log.warn("Failed to create metrics data", e);
        }
    }

    private void logHttpTriggerRequestFinished(Exchange exchange, CamelDebuggerProperties dbgProperties, Exception exception) {
        DeploymentRuntimeProperties runtimeProperties = dbgProperties.getRuntimeProperties(exchange);
        if (!runtimeProperties.getLogLoggingLevel().isInfoLevel()
                && exception == null) {
            return; // Log only if it is info level OR session is failed
        }

        long started = exchange.getProperty(CamelConstants.Properties.START_TIME_MS,
                Long.class);
        long duration = System.currentTimeMillis() - started;

        List<MessageHistory> messageHistory = (List<MessageHistory>) exchange.getAllProperties()
                .getOrDefault(Exchange.MESSAGE_HISTORY, Collections.emptyList());
        String nodeId = messageHistory.stream()
                .map(MessageHistory::getNode)
                .filter(node -> "ref:httpTriggerProcessor".equals(node.getLabel()))
                .findFirst()
                .map(NamedNode::getId)
                .orElse(null);

        String bodyForLogging = "<body not logged>";
        String headersForLogging = payloadExtractor.extractHeadersForLogging(exchange,
                dbgProperties.getMaskedFields(), runtimeProperties.isMaskingEnabled()).toString();
        String exchangePropertiesForLogging = payloadExtractor.extractExchangePropertiesForLogging(
                exchange, dbgProperties.getMaskedFields(), runtimeProperties.isMaskingEnabled()).toString();

        if (runtimeProperties.isLogPayloadEnabled()) {     //Deprecated since 24.4
            bodyForLogging = payloadExtractor.extractBodyForLogging(exchange,
                    dbgProperties.getMaskedFields(), runtimeProperties.isMaskingEnabled());
        }

        if (runtimeProperties.getLogPayload() != null && !runtimeProperties.getLogPayload().isEmpty()) {
            Set<LogPayload> logPayloadSettings = runtimeProperties.getLogPayload();
            headersForLogging = logPayloadSettings.contains(LogPayload.HEADERS) ? headersForLogging : "<headers not logged>";

            exchangePropertiesForLogging = logPayloadSettings.contains(LogPayload.PROPERTIES) ? exchangePropertiesForLogging : "<properties not logged>";

            bodyForLogging = logPayloadSettings.contains(LogPayload.BODY) ? payloadExtractor.extractBodyForLogging(exchange,
                    dbgProperties.getMaskedFields(), dbgProperties.getRuntimeProperties(exchange)
                            .isMaskingEnabled()) : "<body not logged>";
        }

        chainLogger.logHTTPExchangeFinished(exchange, dbgProperties, bodyForLogging, headersForLogging,
                exchangePropertiesForLogging, nodeId, duration, exception);
    }

    private CamelDebuggerProperties getCamelDebuggerProperties(Exchange exchange) {
        CamelDebugger camelDebugger = ((CamelDebugger) exchange.getContext().getDebugger());
        return propertiesService.getProperties(exchange, camelDebugger.getDeploymentId());
    }
}
