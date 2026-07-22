package org.qubership.integration.platform.runtime.catalog.cr.model;

import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;

public interface ResourceBuildOptionsCustomizer {
    void customize(ResourceDeployRequest request, ResourceBuildOptions options);
}
