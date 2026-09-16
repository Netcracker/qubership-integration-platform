package org.qubership.integration.platform.camelk.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MonitoringOptions {
    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private String interval = "30s";
}
