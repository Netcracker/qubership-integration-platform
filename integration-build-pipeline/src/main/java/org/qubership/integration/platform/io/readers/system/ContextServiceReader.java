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
import org.qubership.integration.platform.chain.impl.ContextServiceImpl;
import org.qubership.integration.platform.chain.model.ContextService;
import org.qubership.integration.platform.io.model.exportimport.system.ContextServiceContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.ContextServiceDto;
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
 * Reads a context-service export file into the library {@link ContextService} model.
 *
 * <p>The reader migrates the YAML to the current file version, deserializes it into a
 * {@link ContextServiceDto}, and maps the result to the library model. It mirrors the chain-side
 * {@code ChainReader}: the catalog turns the model into its JPA entity in a later step.
 */
@Component
public class ContextServiceReader {

    private final YAMLMapper yamlMapper;
    private final FileMigrationService fileMigrationService;
    private final Collection<ServiceImportFileMigration> serviceImportFileMigrations;

    public ContextServiceReader(
            @Qualifier("defaultYamlMapper") YAMLMapper yamlMapper,
            FileMigrationService fileMigrationService,
            Collection<ServiceImportFileMigration> serviceImportFileMigrations
    ) {
        this.yamlMapper = yamlMapper;
        this.fileMigrationService = fileMigrationService;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
    }

    /**
     * Reads the context service stored in {@code serviceFile}.
     *
     * @param serviceFile the exported context-service YAML file
     * @throws IllegalArgumentException if the file cannot be read or migrated
     */
    public ContextService read(File serviceFile) {
        try {
            String serviceYaml = migrateToCurrentFileVersion(Files.readString(serviceFile.toPath()));
            ContextServiceDto dto = yamlMapper.readValue(serviceYaml, ContextServiceDto.class);
            return toModel(dto);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to read context service from file " + serviceFile.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Maps an already deserialized context-service export to the library model. Package-visible so
     * tests can exercise the mapping without a file on disk.
     */
    ContextService toModel(ContextServiceDto dto) {
        ContextServiceImpl model = new ContextServiceImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());

        ContextServiceContentDto content = dto.getContent();
        if (content != null) {
            model.setDescription(content.getDescription());
            model.setInternalServiceName(content.getInternalServiceName());
            model.setModifiedWhen(content.getModifiedWhen());
        }

        return model;
    }

    private String migrateToCurrentFileVersion(String serviceYaml) throws Exception {
        List<ImportFileMigration> migrations = serviceImportFileMigrations.stream()
                .map(ImportFileMigration.class::cast)
                .toList();
        return fileMigrationService.migrate(serviceYaml, migrations);
    }
}
