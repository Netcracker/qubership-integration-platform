package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;

import java.util.List;

@Data
@Schema(description = "MCP system filter request object")
public class MCPSystemFilterRequestDTO {
    @Schema(description = "Search string")
    private String searchString;

    @Schema(description = "Filters")
    private List<FilterRequestDTO> filters;
}
