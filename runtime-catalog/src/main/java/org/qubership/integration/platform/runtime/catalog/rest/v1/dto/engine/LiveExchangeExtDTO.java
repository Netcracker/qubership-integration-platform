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
