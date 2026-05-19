package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;
import org.qubership.integration.platform.runtime.catalog.model.dto.user.UserDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemLabelDTO;

import java.util.List;

@Data
@Schema(description = "Create MCP Service request object")
public class MCPSystemResponseDTO {
    private String id;
    private String name;
    private String description;
    private String identifier;
    private String instructions;
    private Long createdWhen;
    private UserDTO createdBy;
    private Long modifiedWhen;
    private UserDTO modifiedBy;
    private List<SystemLabelDTO> labels;

    @Schema(description = "List of chains that is using current service")
    private List<BaseResponse> chains;
}
