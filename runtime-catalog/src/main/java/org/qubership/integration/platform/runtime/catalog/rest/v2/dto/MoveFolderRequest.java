package org.qubership.integration.platform.runtime.catalog.rest.v2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Move folder request object")
public class MoveFolderRequest {
    @Schema(description = "Folder ID")
    String id;

    @Schema(description = "Target folder ID")
    String targetId;
}
