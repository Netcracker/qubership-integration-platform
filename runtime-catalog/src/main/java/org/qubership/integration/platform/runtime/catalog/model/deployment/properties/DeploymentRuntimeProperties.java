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

package org.qubership.integration.platform.runtime.catalog.model.deployment.properties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.model.chain.LogLoggingLevel;
import org.qubership.integration.platform.runtime.catalog.model.chain.LogPayload;
import org.qubership.integration.platform.runtime.catalog.model.chain.SessionsLoggingLevel;

import java.util.Set;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "Chain logging deployment properties")
public class DeploymentRuntimeProperties {
    private static final DeploymentRuntimeProperties DEFAULT_VALUES = DeploymentRuntimeProperties.builder()
            .sessionsLoggingLevel(SessionsLoggingLevel.OFF)
            .logLoggingLevel(LogLoggingLevel.ERROR)
            .logPayload(Set.of(LogPayload.HEADERS, LogPayload.PROPERTIES))
            .logPayloadEnabled(false)
            .dptEventsEnabled(false)
            .maskingEnabled(true)
            .build();

    private SessionsLoggingLevel sessionsLoggingLevel;
    private LogLoggingLevel logLoggingLevel;
    private Set<LogPayload> logPayload;
    @Deprecated
    private boolean logPayloadEnabled;  //Deprecated since 24.4
    private boolean dptEventsEnabled;
    private boolean maskingEnabled;


    public LogLoggingLevel getLogLoggingLevel() {
        return logLoggingLevel == null ? LogLoggingLevel.defaultLevel() : logLoggingLevel;
    }

    public static DeploymentRuntimeProperties getDefaultValues() {
        return DEFAULT_VALUES;
    }
}
