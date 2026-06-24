package org.qubership.integration.platform.runtime.catalog.cr.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Limits {
    private String cpu;
    private String memory;
}
