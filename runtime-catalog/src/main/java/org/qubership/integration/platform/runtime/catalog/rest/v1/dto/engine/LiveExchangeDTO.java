package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.catalog.model.chain.SessionsLoggingLevel;

@Data
@Schema(description = "Information about Live exchange")
public class LiveExchangeDTO {
    @Schema(description = "Exchange id")
    protected String exchangeId;
    @Schema(description = "Deployment id")
    protected String deploymentId;
    @Schema(description = "Session id")
    protected String sessionId;
    @Schema(description = "Chain id")
    protected String chainId;
    @Schema(description = "Duration of current exchange, in ms")
    protected Long duration;
    @Schema(description = "Duration of the whole session exchange participates in, in ms")
    protected Long sessionDuration;
    @Schema(description = "Session start timestamp")
    protected Long sessionStartTime;
    @Schema(description = "Current session log level")
    protected SessionsLoggingLevel sessionLogLevel;
    @Schema(description = "Is current exchange main (initial)")
    protected Boolean main;
}
