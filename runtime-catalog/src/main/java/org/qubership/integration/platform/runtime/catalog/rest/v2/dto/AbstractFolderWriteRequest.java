package org.qubership.integration.platform.runtime.catalog.rest.v2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public abstract class AbstractFolderWriteRequest {

    @Schema(description = "Parent folder ID")
    @Size(max = 255)
    String parentId;

    @Schema(description = "Name")
    @NotBlank
    @Size(max = 255)
    String name;

    @Schema(description = "Description")
    @Size(max = 255)
    String description;
}
