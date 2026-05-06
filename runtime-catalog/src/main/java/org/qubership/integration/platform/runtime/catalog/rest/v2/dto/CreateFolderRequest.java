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
@Schema(description = "Create folder request object")
public class CreateFolderRequest extends AbstractFolderWriteRequest {

    @Schema(description = "Folder ID")
    String id;
}
