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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;

import java.util.Collections;
import java.util.List;

@Slf4j
@ApplicationScoped
@Named("httpTriggerFinishProcessor")
public class HttpTriggerFinishProcessor implements Processor {

    private final ChainRuntimePropertiesService propertiesService;
    private final ChainLogger chainLogger;
    private final MetricsService metricsService;

    @Inject
    public HttpTriggerFinishProcessor(ChainRuntimePropertiesService propertiesService,
                                      ChainLogger chainLogger,
                                      MetricsService metricsService) {
        this.propertiesService = propertiesService;
        this.chainLogger = chainLogger;
        this.metricsService = metricsService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Boolean sessionFailed = exchange.getProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false, Boolean.class);
        Exception exception = sessionFailed ? exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class) : null;
        logHttpTriggerRequestFinished(exchange, exception);
        logMetrics(exchange, exception);
    }

    private void logMetrics(Exchange exchange, Exception exception) {
        try {
            ChainInfo chainInfo = MetadataUtil.getBean(exchange, DeploymentInfo.class).getChain();
            int responseCode = PayloadExtractor.getServletResponseCode(exchange, exception);
            metricsService.processHttpResponseCode(chainInfo, String.valueOf(responseCode));
            metricsService.processHttpTriggerPayloadSize(exchange);
        } catch (Exception e) {
            log.warn("Failed to create metrics data", e);
        }
    }

    private void logHttpTriggerRequestFinished(
            Exchange exchange,
            Exception exception
    ) {
        ChainRuntimeProperties runtimeProperties = propertiesService.getRuntimeProperties(exchange);
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

        chainLogger.logHTTPExchangeFinished(exchange, nodeId, duration, exception);
    }
}
