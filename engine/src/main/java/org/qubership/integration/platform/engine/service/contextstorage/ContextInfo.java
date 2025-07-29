package org.qubership.integration.platform.engine.service.contextstorage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContextInfo {
    private Long createTimestamp;
    private Long activeTillTimestamp;
}

