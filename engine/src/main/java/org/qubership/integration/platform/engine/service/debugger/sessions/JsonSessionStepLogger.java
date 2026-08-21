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
import org.qubership.integration.platform.engine.service.debugger.logging.LogExchangeMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static net.logstash.logback.marker.Markers.append;

@Component
@ConditionalOnProperty(name = "qip.logging.format", havingValue = "json", matchIfMissing = true)
public class JsonSessionStepLogger implements SessionStepJsonLogger {

    private static final Logger LOG = LoggerFactory.getLogger(JsonSessionStepLogger.class);
    private final LogExchangeMarkers logExchangeMarkers;

    public JsonSessionStepLogger(LogExchangeMarkers logExchangeMarkers) {
        this.logExchangeMarkers = logExchangeMarkers;
    }

    @Override
    public void logAfter(SessionStepLogRecord record, SessionLogDetails details) {
        if (details == null || details == SessionLogDetails.OFF) {
            return;
        }
        if (details == SessionLogDetails.SENDERS
                && !ChainElementType.isElementForInfoSessionsLevel(
                ChainElementType.fromString(record.camelElementName()))) {
            return;
        }

        LogstashMarker markers = append("domain", record.domain())
                .and(append("domain_type",
                        record.domainType() == null ? null : record.domainType().name()))
                .and(append("snapshot", record.snapshotName()))
                .and(append("parent_element_id", record.parentElementId()))
                .and(logExchangeMarkers.buildExchangeMarkers(
                        record.bodyAfter(), record.headersAfter(), record.propertiesAfter()));

        LOG.info(markers, buildMessage(record));
    }

    private String buildMessage(SessionStepLogRecord record) {
        return String.format("Session step %s (%s) finished with status %s",
                record.elementName(),
                record.chainElementId(),
                record.executionStatus());
    }
}
