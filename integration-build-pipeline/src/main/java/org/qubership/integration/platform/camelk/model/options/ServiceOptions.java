package org.qubership.integration.platform.camelk.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ServiceOptions {
    @Builder.Default
    private boolean enabled = true;
}
