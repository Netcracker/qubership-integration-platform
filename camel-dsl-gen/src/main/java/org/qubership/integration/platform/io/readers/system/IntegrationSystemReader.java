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
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.ImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

/**
 * Reads an integration-system export file into the library {@link IntegrationSystemDto} model.
 *
 * <p>The reader migrates the YAML to the current file version and deserializes it into an
 * {@link IntegrationSystemDto}. It mirrors the chain-side {@code ChainReader} and the
 * {@code McpServiceReader}: the moved system DTO graph is both the import format and the model the
 * catalog maps to its JPA entities. The specification groups and system models that share the
 * export directory are read separately by the catalog, which walks the directory tree.
 */
@Component
public class IntegrationSystemReader {

    private final YAMLMapper yamlMapper;
    private final FileMigrationService fileMigrationService;
    private final Collection<ServiceImportFileMigration> serviceImportFileMigrations;

    public IntegrationSystemReader(
            @Qualifier("defaultYamlMapper") YAMLMapper yamlMapper,
            FileMigrationService fileMigrationService,
            Collection<ServiceImportFileMigration> serviceImportFileMigrations
    ) {
        this.yamlMapper = yamlMapper;
        this.fileMigrationService = fileMigrationService;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
    }

    /**
     * Reads the integration system stored in {@code systemFile}.
     *
     * @param systemFile the exported integration-system YAML file
     * @throws IllegalArgumentException if the file cannot be read or migrated
     */
    public IntegrationSystemDto read(File systemFile) {
        try {
            String systemYaml = migrateToCurrentFileVersion(Files.readString(systemFile.toPath()));
            return toModel(yamlMapper.readValue(systemYaml, IntegrationSystemDto.class));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to read integration system from file " + systemFile.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the deserialized integration-system export as the library model. The moved DTO graph
     * already carries library-owned types, so it serves as the model directly. Package-visible so
     * tests can exercise the mapping without a file on disk.
     */
    IntegrationSystemDto toModel(IntegrationSystemDto dto) {
        return dto;
    }

    private String migrateToCurrentFileVersion(String systemYaml) throws Exception {
        List<ImportFileMigration> migrations = serviceImportFileMigrations.stream()
                .map(ImportFileMigration.class::cast)
                .toList();
        return fileMigrationService.migrate(systemYaml, migrations);
    }
}
