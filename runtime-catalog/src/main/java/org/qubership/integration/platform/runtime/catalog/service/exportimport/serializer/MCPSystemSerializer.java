package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.MCPServiceDto;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedMCPSystemObject;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.MCPServiceDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class MCPSystemSerializer {
    private final YAMLMapper yamlMapper;
    private final MCPServiceDtoMapper mcpServiceDtoMapper;
    private final FileMigrationService fileMigrationService;

    @Autowired
    public MCPSystemSerializer(
            @Qualifier("yamlExportImportMapper") YAMLMapper yamlExportImportMapper,
            MCPServiceDtoMapper mcpServiceDtoMapper,
            FileMigrationService fileMigrationService
    ) {
        this.yamlMapper = yamlExportImportMapper;
        this.mcpServiceDtoMapper = mcpServiceDtoMapper;
        this.fileMigrationService = fileMigrationService;
    }

    public ExportedSystemObject serialize(MCPSystem system) throws JsonProcessingException {
        MCPServiceDto mcpServiceDto = mcpServiceDtoMapper.toExternalEntity(system);
        ObjectNode systemNode = fileMigrationService.revertMigrationIfNeeded(yamlMapper.valueToTree(mcpServiceDto));
        return new ExportedMCPSystemObject(system.getId(), systemNode);
    }
}
