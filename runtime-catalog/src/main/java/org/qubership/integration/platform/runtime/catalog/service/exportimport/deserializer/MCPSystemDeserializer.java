package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.MCPServiceDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.MCPServiceDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.mcp.MCPServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;

@Component
public class MCPSystemDeserializer {
    private final YAMLMapper yamlMapper;
    private final FileMigrationService fileMigrationService;
    private final Collection<MCPServiceImportFileMigration> importFileMigrations;
    private final MCPServiceDtoMapper mcpServiceDtoMapper;

    @Autowired
    public MCPSystemDeserializer(
            @Qualifier("yamlExportImportMapper") YAMLMapper yamlMapper,
            FileMigrationService fileMigrationService,
            Collection<MCPServiceImportFileMigration> importFileMigrations,
            MCPServiceDtoMapper mcpServiceDtoMapper
    ) {
        this.yamlMapper = yamlMapper;
        this.fileMigrationService = fileMigrationService;
        this.importFileMigrations = importFileMigrations;
        this.mcpServiceDtoMapper = mcpServiceDtoMapper;
    }

    public MCPSystem deserialize(File serviceFile) {
        try {
            String serviceData = fileMigrationService.migrate(
                    Files.readString(serviceFile.toPath()),
                    importFileMigrations.stream().map(ImportFileMigration.class::cast).toList()
            );
            MCPServiceDto mcpServiceDto = yamlMapper.readValue(serviceData, MCPServiceDto.class);
            return mcpServiceDtoMapper.toInternalEntity(mcpServiceDto);
        } catch (ServiceImportException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
