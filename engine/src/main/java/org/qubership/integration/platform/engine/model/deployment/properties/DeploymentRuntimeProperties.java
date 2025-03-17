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

package org.qubership.integration.platform.engine.model.deployment.properties;

import lombok.*;
import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;

import java.util.Set;

import static java.util.Objects.nonNull;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DeploymentRuntimeProperties {
    private static final DeploymentRuntimeProperties DEFAULT_VALUES = DeploymentRuntimeProperties.builder()
        .sessionsLoggingLevel(SessionsLoggingLevel.OFF)
        .logLoggingLevel(LogLoggingLevel.ERROR)
        .logPayload(Set.of(LogPayload.HEADERS, LogPayload.PROPERTIES))
        .dptEventsEnabled(false)
        .maskingEnabled(true)
        .build();

    @Getter(AccessLevel.PRIVATE)
    private SessionsLoggingLevel sessionsLoggingLevel;
    private LogLoggingLevel logLoggingLevel;
    @Deprecated
    private boolean logPayloadEnabled; //Deprecated since 24.4
    private Set<LogPayload> logPayload;
    private boolean dptEventsEnabled;
    private boolean maskingEnabled;

    public SessionsLoggingLevel calculateSessionLevel(Exchange exchange) {
        // At first, we are looking for specific header, that sets the logging level.
        Boolean headerValue = exchange.getMessage().getHeader(
            Headers.TRACE_ME,
            Boolean.FALSE, Boolean.class);
        // After that we are looking for exchange property with same behavior.
        Boolean propertyValue = exchange.getProperty(
            Properties.TRACE_ME,
            headerValue, Boolean.class);

        boolean isFullReportingSet = nonNull(propertyValue) && propertyValue;
        if (isFullReportingSet) {
            return SessionsLoggingLevel.DEBUG;
        }

        // If logging level is not override by header or property, set it from properties.
        return sessionsLoggingLevel;
    }

    public LogLoggingLevel getLogLoggingLevel() {
        return logLoggingLevel == null ? LogLoggingLevel.defaultLevel() : logLoggingLevel;
    }

    public static DeploymentRuntimeProperties getDefaultValues() {
        return DEFAULT_VALUES;
    }
}
