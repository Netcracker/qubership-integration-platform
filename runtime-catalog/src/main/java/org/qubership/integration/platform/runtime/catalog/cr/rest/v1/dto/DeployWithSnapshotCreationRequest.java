package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentSnapshotAction;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeployWithSnapshotCreationRequest {
    @NotEmpty(message = "At least one domain should be specified")
    List<String> domains;

    @NotEmpty(message = "At least one chain ID should be specified")
    @Builder.Default
    private List<String> chainIds = Collections.emptyList();

    @Builder.Default
    @Schema(description = "Which snapshot should be taken during bulk deploy")
    private BulkDeploymentSnapshotAction snapshotAction = BulkDeploymentSnapshotAction.CREATE_NEW;

    @Builder.Default
    private DeployMode mode = DeployMode.REWRITE;
}
