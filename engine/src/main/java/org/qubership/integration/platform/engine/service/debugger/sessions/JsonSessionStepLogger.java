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

package org.qubership.integration.platform.engine.service.debugger.sessions;

import net.logstash.logback.marker.LogstashMarker;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.service.debugger.logging.AbstractChainLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static net.logstash.logback.marker.Markers.append;

/**
 * Logs a JSON record for each finished session step. Isolated from the OpenSearch writer so it can
 * stay once OpenSearch support is removed. Only "after" records are emitted (never "before").
 */
@Component
@ConditionalOnProperty(name = "qip.logging.format", havingValue = "json", matchIfMissing = true)
public class JsonSessionStepLogger implements SessionStepJsonLogger {

    private static final Logger LOG = LoggerFactory.getLogger(JsonSessionStepLogger.class);

    private static final String MDC_SESSION_ID = "internalProperty_sessionId";
    private static final String MDC_CHAIN_ID = "chainId";
    private static final String MDC_CHAIN = "chainName";
    private static final String MDC_CHAIN_ELEMENT_ID = "elementId";
    private static final String MDC_CHAIN_ELEMENT = "elementName";

    @Override
    public void logAfter(SessionElementElastic element, SessionLogDetails details) {
        if (details == null || details == SessionLogDetails.OFF) {
            return;
        }
        if (details == SessionLogDetails.SENDERS
                && !ChainElementType.isElementForInfoSessionsLevel(
                ChainElementType.fromString(element.getCamelElementName()))) {
            return;
        }

        LogstashMarker markers = append("domain", element.getDomain())
                .and(append("domain_type",
                        element.getDomainType() == null ? null : element.getDomainType().name()))
                .and(append("snapshot", element.getSnapshotName()))
                .and(append("parent_element_id", element.getParentElementId()));

        setLoggerContext(element);
        try {
            LOG.info(markers, buildMessage(element));
        } finally {
            clearLoggerContext();
        }
    }

    private void setLoggerContext(SessionElementElastic element) {
        AbstractChainLogger.updateMDCProperty(MDC_SESSION_ID, element.getSessionId());
        AbstractChainLogger.updateMDCProperty(MDC_CHAIN_ID, element.getChainId());
        AbstractChainLogger.updateMDCProperty(MDC_CHAIN, element.getChainName());
        AbstractChainLogger.updateMDCProperty(MDC_CHAIN_ELEMENT_ID, element.getChainElementId());
        AbstractChainLogger.updateMDCProperty(MDC_CHAIN_ELEMENT, element.getElementName());
    }

    private void clearLoggerContext() {
        MDC.remove(MDC_SESSION_ID);
        MDC.remove(MDC_CHAIN_ID);
        MDC.remove(MDC_CHAIN);
        MDC.remove(MDC_CHAIN_ELEMENT_ID);
        MDC.remove(MDC_CHAIN_ELEMENT);
    }

    private String buildMessage(SessionElementElastic element) {
        return String.format("Session step %s (%s) finished with status %s",
                element.getElementName(),
                element.getChainElementId(),
                element.getExecutionStatus());
    }
}
