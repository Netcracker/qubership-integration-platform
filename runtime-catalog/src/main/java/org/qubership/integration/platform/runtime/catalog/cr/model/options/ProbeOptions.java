package org.qubership.integration.platform.runtime.catalog.cr.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ProbeOptions {
    private boolean enabled;
    private String schema;
    private int initialDelay;
    private int timeout;
    private int period;
    private int successThreshold;
    private int failureThreshold;
    private String probe;
    private int port;
}
