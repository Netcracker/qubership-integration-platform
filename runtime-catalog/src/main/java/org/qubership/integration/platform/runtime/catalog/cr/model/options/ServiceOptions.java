package org.qubership.integration.platform.runtime.catalog.cr.model.options;

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
