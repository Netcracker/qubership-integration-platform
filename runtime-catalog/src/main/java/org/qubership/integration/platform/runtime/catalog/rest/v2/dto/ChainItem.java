package org.qubership.integration.platform.runtime.catalog.rest.v2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainLabelDTO;

import java.util.List;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Chain item response object")
public class ChainItem extends CatalogItem {
    @Schema(description = "Assigned labels")
    private List<ChainLabelDTO> labels;
}
