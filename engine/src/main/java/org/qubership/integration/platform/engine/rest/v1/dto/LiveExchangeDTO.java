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

package org.qubership.integration.platform.engine.rest.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;

@Data
@Builder
@Schema(description = "Information about Live exchange")
public class LiveExchangeDTO {
    @Schema(description = "Exchange id")
    private String exchangeId;
    @Schema(description = "Deployment id")
    private String deploymentId;
    @Schema(description = "Session id")
    private String sessionId;
    @Schema(description = "Chain id")
    protected String chainId;
    @Schema(description = "Duration of current exchange, in ms")
    private Long duration;
    @Schema(description = "Duration of the whole session exchange participates in, in ms")
    private Long sessionDuration;
    @Schema(description = "Session start timestamp")
    private Long sessionStartTime;
    @Schema(description = "Current session log level")
    private SessionsLoggingLevel sessionLogLevel;
    @Schema(description = "Is current exchange main (initial)")
    private Boolean main;
}
