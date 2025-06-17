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
public class CreateFolderRequest {
    @Schema(description = "Folder ID")
    String id;

    @Schema(description = "Parent folder ID")
    String parentId;

    @Schema(description = "Name")
    String name;

    @Schema(description = "Description")
    String description;
}
