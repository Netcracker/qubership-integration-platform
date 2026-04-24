package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto;

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
