package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemLabelDTO;

import java.util.List;

@Data
@Schema(description = "MCP Service request object")
public class MCPSystemRequestDTO {
    @Schema(description = "Name")
    private String name;

    @Schema(description = "description")
    private String description;

    @Schema(description = "MCP server name")
    private String identifier;

    @Schema(description = "MCP server instructions")
    private String instructions;

    @Schema(description = "Labels assigned to the service")
    private List<SystemLabelDTO> labels;
}
