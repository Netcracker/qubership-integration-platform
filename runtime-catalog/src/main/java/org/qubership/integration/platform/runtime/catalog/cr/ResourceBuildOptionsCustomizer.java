package org.qubership.integration.platform.runtime.catalog.cr;

import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;

public interface ResourceBuildOptionsCustomizer {
    void customize(ResourceDeployRequest request, ResourceBuildOptions options);
}
