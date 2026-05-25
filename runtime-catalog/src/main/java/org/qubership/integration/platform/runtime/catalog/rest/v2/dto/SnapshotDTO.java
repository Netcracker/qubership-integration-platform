package org.qubership.integration.platform.runtime.catalog.rest.v2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;
import org.qubership.integration.platform.runtime.catalog.model.dto.dependency.DependencyResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.ElementResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.snapshot.SnapshotLabelDTO;

import java.util.List;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Full (with elements) response object for a chain snapshot")
public class SnapshotDTO extends BaseResponse {
    @Schema(description = "Elements contained in snapshot")
    private List<ElementResponse> elements;

    @Schema(description = "Connections between elements of the snapshot")
    private List<DependencyResponse> dependencies;

    @Schema(description = "Labels assigned to the chain")
    private List<SnapshotLabelDTO> labels;

    @Schema(description = "Id of default swimlane on chain graph (if any)")
    private String defaultSwimlaneId;

    @Schema(description = "Id of reuse swimlane on chain graph (if any)")
    private String reuseSwimlaneId;

    @Schema(description = "Chain")
    private BaseResponse chain;
}
