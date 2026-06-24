package org.qubership.integration.platform.runtime.catalog.cr.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class HealthOptions {
    @Builder.Default
    private ProbeOptions liveness = new ProbeOptions();

    @Builder.Default
    private ProbeOptions readiness = new ProbeOptions();

    @Builder.Default
    private ProbeOptions startup = new ProbeOptions();
}
