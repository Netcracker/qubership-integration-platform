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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SpecificationGroupDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SpecificationGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.MigrationException;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;

@Slf4j
@Component
public class ServiceDeserializer {
    private final YAMLMapper yamlMapper;
    private final VersionsGetterService versionsGetterService;
    private final IntegrationSystemDtoMapper integrationSystemDtoMapper;
    private final SpecificationGroupDtoMapper specificationGroupDtoMapper;
    private final SystemModelDtoMapper systemModelDtoMapper;
    private final FileMigrationService fileMigrationService;
    private final Collection<ServiceImportFileMigration> importFileMigrations;

    @Autowired
    public ServiceDeserializer(
            YAMLMapper yamlExportImportMapper,
            VersionsGetterService versionsGetterService,
            IntegrationSystemDtoMapper integrationSystemDtoMapper,
            SpecificationGroupDtoMapper specificationGroupDtoMapper,
            SystemModelDtoMapper systemModelDtoMapper,
            FileMigrationService fileMigrationService,
            Collection<ServiceImportFileMigration> importFileMigrations
    ) {
        this.yamlMapper = yamlExportImportMapper;
        this.versionsGetterService = versionsGetterService;
        this.integrationSystemDtoMapper = integrationSystemDtoMapper;
        this.specificationGroupDtoMapper = specificationGroupDtoMapper;
        this.systemModelDtoMapper = systemModelDtoMapper;
        this.fileMigrationService = fileMigrationService;
        this.importFileMigrations = importFileMigrations;
    }

    public IntegrationSystem deserializeSystem(File serviceFile) {
        try {
            File serviceDirectory = serviceFile.getParentFile();
            JsonNode serviceNode = yamlMapper.readTree(serviceFile);
            Collection<Integer> versions = versionsGetterService.getVersions(serviceNode);
            String serviceData = fileMigrationService.migrate(
                    Files.readString(serviceFile.toPath()),
                    importFileMigrations.stream().map(ImportFileMigration.class::cast).toList()
            );
            IntegrationSystemDto integrationSystemDto = yamlMapper.readValue(serviceData, IntegrationSystemDto.class);
            IntegrationSystem integrationSystem = integrationSystemDtoMapper.toInternalEntity(integrationSystemDto);

            Collection<File> files = listFiles(serviceDirectory);

            getFilesData(files, SPECIFICATION_GROUP_FILE_PREFIX).forEach(node ->
                    buildAndAddSpecificationGroup(node, versions, integrationSystem));

            getFilesData(files, SPECIFICATION_FILE_PREFIX).forEach(node ->
                    buildAndAddSpecification(node, versions, integrationSystem.getSpecificationGroups(), serviceDirectory));

            return integrationSystem;
        } catch (ServiceImportException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ObjectNode migrate(ObjectNode node, Collection<Integer> versions) throws MigrationException {
        node.set("migrations", TextNode.valueOf(versions.stream().sorted().toList().toString()));
        return fileMigrationService.migrate(
                node,
                importFileMigrations.stream().map(ImportFileMigration.class::cast).toList()
        );
    }

    private static Collection<File> listFiles(File serviceDirectory) {
        try (Stream<Path> fs = Files.walk(serviceDirectory.toPath())) {
            return fs.filter(Files::isRegularFile)
                    .map(Path::toFile).toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list service directory", e);
        }
    }

    private Stream<ObjectNode> getFilesData(Collection<File> files, String namePrefix) {
        return files.stream()
                .filter(file -> file.getName().startsWith(namePrefix))
                .map(file -> {
                    try {
                        JsonNode node = yamlMapper.readTree(file);
                        if (!node.isObject()) {
                            throw new RuntimeException("Expected object node but got " + node.getNodeType().name());
                        }
                        return (ObjectNode) node;
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
    }

    private void buildAndAddSpecificationGroup(
            ObjectNode node,
            Collection<Integer> versions,
            IntegrationSystem integrationSystem
    ) {
        try {
            ObjectNode migratedNode = migrate(node, versions);
            SpecificationGroupDto specificationGroupDto = yamlMapper.treeToValue(migratedNode, SpecificationGroupDto.class);
            SpecificationGroup specificationGroup = specificationGroupDtoMapper.toInternalEntity(specificationGroupDto);
            if (Objects.equals(specificationGroupDto.getContent().getParentId(), integrationSystem.getId())) {
                integrationSystem.addSpecificationGroup(specificationGroup);
            }
        } catch (MigrationException exception) {
            throw new RuntimeException("Failed to migrate specification group data", exception);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Failed to construct specification group from YAML", exception);
        }
    }

    private void buildAndAddSpecification(
            ObjectNode node,
            Collection<Integer> versions,
            Collection<SpecificationGroup> specificationGroups,
            File resourceDirectory
    ) {
        try {
            ObjectNode migratedNode = migrate(node, versions);
            SystemModelDto systemModelDto = yamlMapper.treeToValue(migratedNode, SystemModelDto.class);
            SystemModel systemModel = systemModelDtoMapper.toInternalEntity(systemModelDto);
            specificationGroups.stream()
                    .filter(group -> Objects.equals(group.getId(), systemModelDto.getContent().getParentId()))
                    .findFirst()
                    .ifPresent(group -> group.addSystemModel(systemModel));
            systemModelDto.getContent().getSpecificationSources().forEach(specificationSourceDto -> {
                var specificationSourceBuilder = SpecificationSource.builder();
                specificationSourceBuilder
                        .id(specificationSourceDto.getId())
                        .name(specificationSourceDto.getName())
                        .description(specificationSourceDto.getDescription())
                        .createdBy(specificationSourceDto.getCreatedBy())
                        .createdWhen(specificationSourceDto.getCreatedWhen())
                        .modifiedBy(specificationSourceDto.getModifiedBy())
                        .modifiedWhen(specificationSourceDto.getModifiedWhen())
                        .sourceHash(specificationSourceDto.getSourceHash())
                        .isMainSource(specificationSourceDto.isMainSource());
                Path sourcePath = resourceDirectory.toPath().resolve(specificationSourceDto.getFileName());
                if (Files.exists(sourcePath)) {
                    try {
                        specificationSourceBuilder.source(Files.readString(sourcePath));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read specification source", e);
                    }
                } else {
                    log.warn("Specification source file not found: {}", specificationSourceDto.getFileName());
                }
                SpecificationSource specificationSource = specificationSourceBuilder.build();
                systemModel.addProvidedSpecificationSource(specificationSource);
            });
        } catch (MigrationException exception) {
            throw new RuntimeException("Failed to migrate specification data", exception);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Failed to construct specification from YAML", exception);
        }
    }
}
