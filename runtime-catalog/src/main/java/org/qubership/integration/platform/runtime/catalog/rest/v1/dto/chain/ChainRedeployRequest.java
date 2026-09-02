package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Chain redeploy request object")
public class ChainRedeployRequest {
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Whether changes on graph is unsaved in the chain")
    private Boolean unsavedChanges;
}
