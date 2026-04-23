package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceDeployRequest {
    @NotBlank(message = "Resource name is required")
    String name;

    @NotEmpty(message = "At least one snapshot ID should be specified")
    @Builder.Default
    private List<String> snapshotIds = Collections.emptyList();

    @Builder.Default
    DeployMode mode = DeployMode.REWRITE;
}
