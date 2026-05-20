package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationsConfigurationOptions {
    @Builder.Default
    private boolean camelKSourcesUtilized = false;

    private String configurationLocation;

    public boolean isConfigurationConfigMapNeeded() {
        return !camelKSourcesUtilized && StringUtils.isBlank(configurationLocation);
    }
}
