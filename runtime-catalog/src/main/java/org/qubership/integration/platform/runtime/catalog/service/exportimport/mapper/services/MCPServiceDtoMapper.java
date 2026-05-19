package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.MCPServiceContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.MCPServiceDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystemLabel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.mcp.MCPServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MCPServiceDtoMapper implements ExternalEntityMapper<MCPSystem, MCPServiceDto> {
    private final URI schemaUri;
    private final List<MCPServiceImportFileMigration> migrations;

    @Autowired
    public MCPServiceDtoMapper(
            @Value("${qip.json.schemas.mcp-service:http://qubership.org/schemas/product/qip/mcp-service}") URI schemaUri,
            List<MCPServiceImportFileMigration> migrations
    ) {
        this.schemaUri = schemaUri;
        this.migrations = migrations;
    }

    @Override
    public MCPSystem toInternalEntity(MCPServiceDto mcpServiceDto) {
        MCPSystem system = MCPSystem.builder()
                .id(mcpServiceDto.getId())
                .name(mcpServiceDto.getName())
                .description(mcpServiceDto.getContent().getDescription())
                .identifier(mcpServiceDto.getContent().getIdentifier())
                .instructions(mcpServiceDto.getContent().getInstructions())
                .createdBy(mcpServiceDto.getContent().getCreatedBy())
                .createdWhen(mcpServiceDto.getContent().getCreatedWhen())
                .modifiedBy(mcpServiceDto.getContent().getModifiedBy())
                .modifiedWhen(mcpServiceDto.getContent().getModifiedWhen())
                .build();
        system.setLabels(mcpServiceDto
                .getContent()
                .getLabels()
                .stream()
                .map(name -> new MCPSystemLabel(name, system))
                .collect(Collectors.toSet()));
        return system;
    }

    @Override
    public MCPServiceDto toExternalEntity(MCPSystem system) {
        return MCPServiceDto.builder()
                .id(system.getId())
                .name(system.getName())
                .schema(schemaUri)
                .content(MCPServiceContentDto.builder()
                        .description(system.getDescription())
                        .identifier(system.getIdentifier())
                        .instructions(system.getInstructions())
                        .labels(system.getLabels().stream().map(MCPSystemLabel::getName).toList())
                        .migrations(migrations
                                .stream()
                                .map(ImportFileMigration::getVersion)
                                .sorted()
                                .toList()
                                .toString())
                        .build())
                .build();
    }
}
