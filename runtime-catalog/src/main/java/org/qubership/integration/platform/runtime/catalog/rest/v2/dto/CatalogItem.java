package org.qubership.integration.platform.runtime.catalog.rest.v2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.ItemType;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Catalog item response object")
public class CatalogItem extends BaseResponse {
    @Schema(description = "Parent folder id (if any)")
    private String parentId;

    @Schema(description = "Item type")
    private ItemType itemType;
}
