package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.mapstruct.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystemLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemResponseDTO;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(
        componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        collectionMappingStrategy = CollectionMappingStrategy.SETTER_PREFERRED,
        uses = {
                MapperUtils.class
        }
)
public interface MCPSystemMapper {
    List<MCPSystemResponseDTO> toResponseDtos(List<MCPSystem> systems);

    MCPSystemResponseDTO toResponseDto(MCPSystem system);

    @Mapping(target = "labels", ignore = true)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdWhen", ignore = true)
    @Mapping(target = "modifiedWhen", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "modifiedBy", ignore = true)
    @Mapping(target = "chains", ignore = true)
    MCPSystem updateWithoutLabels(@MappingTarget MCPSystem contextSystem, MCPSystemRequestDTO request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "system", ignore = true)
    MCPSystemLabel updateLabel(@MappingTarget MCPSystemLabel label, SystemLabelDTO labelDTO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "system", ignore = true)
    MCPSystemLabel asLabel(SystemLabelDTO labelDTO);

    List<MCPSystemLabel> asLabels(List<SystemLabelDTO> labelDTOs);

    SystemLabelDTO asLabelDTO(MCPSystemLabel label);

    List<SystemLabelDTO> asLabelDTOs(List<MCPSystemLabel> labels);
}
