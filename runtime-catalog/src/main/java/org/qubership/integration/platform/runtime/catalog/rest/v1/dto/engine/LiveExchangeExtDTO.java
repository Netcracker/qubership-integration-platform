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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Information about Live exchange with origin info")
public class LiveExchangeExtDTO extends LiveExchangeDTO {
    @Schema(description = "Engine pod ip address")
    protected String podIp;
    @Schema(description = "Chain name")
    protected String chainName;

    public LiveExchangeExtDTO(LiveExchangeDTO liveExchangeDTO, String podIp) {
        this.exchangeId = liveExchangeDTO.exchangeId;
        this.deploymentId = liveExchangeDTO.deploymentId;
        this.sessionId = liveExchangeDTO.sessionId;
        this.chainId = liveExchangeDTO.chainId;
        this.duration = liveExchangeDTO.duration;
        this.sessionDuration = liveExchangeDTO.sessionDuration;
        this.sessionStartTime = liveExchangeDTO.sessionStartTime;
        this.sessionLogLevel = liveExchangeDTO.sessionLogLevel;
        this.main = liveExchangeDTO.main;
        this.podIp = podIp;
    }
}
