package org.qubership.integration.platform.engine.model.logging;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoggedPayloadValues {
    private String headers;
    private String properties;
    private String body;
}
