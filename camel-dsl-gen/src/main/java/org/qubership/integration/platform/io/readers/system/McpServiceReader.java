/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.io.readers.system;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.chain.impl.McpServiceImpl;
import org.qubership.integration.platform.chain.model.McpService;
import org.qubership.integration.platform.io.model.exportimport.system.MCPServiceContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.MCPServiceDto;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.ImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.mcp.MCPServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

/**
 * Reads an MCP service export file into the library {@link McpService} model.
 *
 * <p>The reader migrates the YAML to the current file version, deserializes it into an
 * {@link MCPServiceDto}, and maps the result to the library model. It mirrors the chain-side
 * {@code ChainReader}: the catalog turns the model into its JPA entity in a later step.
 */
@Component
public class McpServiceReader {

    private final YAMLMapper yamlMapper;
    private final FileMigrationService fileMigrationService;
    private final Collection<MCPServiceImportFileMigration> mcpServiceImportFileMigrations;

    public McpServiceReader(
            @Qualifier("defaultYamlMapper") YAMLMapper yamlMapper,
            FileMigrationService fileMigrationService,
            Collection<MCPServiceImportFileMigration> mcpServiceImportFileMigrations
    ) {
        this.yamlMapper = yamlMapper;
        this.fileMigrationService = fileMigrationService;
        this.mcpServiceImportFileMigrations = mcpServiceImportFileMigrations;
    }

    /**
     * Reads the MCP service stored in {@code serviceFile}.
     *
     * @param serviceFile the exported MCP service YAML file
     * @throws IllegalArgumentException if the file cannot be read or migrated
     */
    public McpService read(File serviceFile) {
        try {
            String serviceYaml = migrateToCurrentFileVersion(Files.readString(serviceFile.toPath()));
            MCPServiceDto dto = yamlMapper.readValue(serviceYaml, MCPServiceDto.class);
            return toModel(dto);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to read MCP service from file " + serviceFile.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Maps an already deserialized MCP service export to the library model. Package-visible so tests
     * can exercise the mapping without a file on disk.
     */
    McpService toModel(MCPServiceDto dto) {
        McpServiceImpl model = new McpServiceImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());

        MCPServiceContentDto content = dto.getContent();
        if (content != null) {
            model.setDescription(content.getDescription());
            model.setIdentifier(content.getIdentifier());
            model.setInstructions(content.getInstructions());
            model.setCreatedBy(content.getCreatedBy());
            model.setCreatedWhen(content.getCreatedWhen());
            model.setModifiedBy(content.getModifiedBy());
            model.setModifiedWhen(content.getModifiedWhen());
            model.setLabels(content.getLabels());
        }

        return model;
    }

    private String migrateToCurrentFileVersion(String serviceYaml) throws Exception {
        List<ImportFileMigration> migrations = mcpServiceImportFileMigrations.stream()
                .map(ImportFileMigration.class::cast)
                .toList();
        return fileMigrationService.migrate(serviceYaml, migrations);
    }
}
