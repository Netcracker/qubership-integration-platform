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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ImportFileMigrationUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportUtils.getFileContent;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportUtils.getNodeAsText;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_VERSION_FIELD_OLD;

@Slf4j
@Component
public class ServiceDeserializer {

    private final YAMLMapper yamlMapper;
    private final Map<Integer, ServiceImportFileMigration> importFileMigrations;

    @Autowired
    public ServiceDeserializer(YAMLMapper yamlExportImportMapper,
                               List<ServiceImportFileMigration> importFileMigrations) {
        this.yamlMapper = yamlExportImportMapper;
        this.importFileMigrations = importFileMigrations.stream()
                .collect(Collectors.toMap(ImportFileMigration::getVersion, Function.identity()));
    }

    public IntegrationSystem deserializeSystem(ObjectNode serviceNode, File serviceDirectory) {
        // TODO Future migrations should be applied before this method call for all files
        serviceNode = enrichServiceNode(serviceNode, serviceDirectory);

        IntegrationSystem system;
        try {
            serviceNode = migrateToActualFileVersion(serviceNode);
            system = yamlMapper.treeToValue(serviceNode, IntegrationSystem.class);
            // Setting parent for spec groups and specifications so hibernate will save them

            system.getSpecificationGroups().forEach(
                    specificationGroup -> {
                        specificationGroup.setSystem(system);
                        specificationGroup.getSystemModels().forEach(
                                systemModel -> systemModel.setSpecificationGroup(specificationGroup));
                    });
        } catch (ServiceImportException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return system;
    }

    private ObjectNode enrichServiceNode(ObjectNode serviceNode, File serviceDirectory) {
        Path serviceDirectoryPath = serviceDirectory.toPath();
        String serviceId = serviceNode.get(AbstractSystemEntity.Fields.id).asText();
        try (Stream<Path> fs = Files.walk(serviceDirectory.toPath())) {
            List<File> listOfFile = fs.filter(Files::isRegularFile)
                    .map(Path::toFile).toList();

            // Reading all the files into Nodes
            ArrayList<JsonNode> specificationGroupNodes = new ArrayList<>();
            Map<String, List<JsonNode>> specificationNodesMap = new HashMap<>();
            Map<String, List<File>> sourceFiles = new HashMap<>();
            for (File file : listOfFile) {
                String fileName = file.getName();
                String parentDirName = Optional.ofNullable(file.getParentFile())
                    .map(File::toPath)
                    .map(serviceDirectoryPath::relativize)
                    .map(Path::toString)
                    .orElse("");

                if (fileName.startsWith(SPECIFICATION_GROUP_FILE_PREFIX)) {
                    JsonNode specGroupNode = yamlMapper.readTree(file);
                    String specGroupParentId = specGroupNode.get(PARENT_ID_FIELD_NAME).asText();
                    if (!serviceId.equals(specGroupParentId)) {
                        continue;
                    }
                    specificationGroupNodes.add(specGroupNode);

                } else if (fileName.startsWith(SPECIFICATION_FILE_PREFIX)) {
                    JsonNode specNode = yamlMapper.readTree(file);
                    String specGroupId = specNode.get(PARENT_ID_FIELD_NAME).asText();
                    specificationNodesMap.computeIfAbsent(specGroupId, k -> new ArrayList<>()).add(specNode);

                } else if (parentDirName.startsWith(SOURCE_YAML_NAME_PREFIX)) {
                    sourceFiles.computeIfAbsent(parentDirName, k -> new ArrayList<>()).add(file);
                }
            }

            setSpecifications(serviceNode, specificationGroupNodes, specificationNodesMap);

            setSpecificationSources(serviceNode, sourceFiles);

        } catch (IOException e) {
            throw new RuntimeException("Unexpected error while service enriching", e);
        }
        return serviceNode;
    }

    private void setSpecifications(ObjectNode serviceNode, ArrayList<JsonNode> specificationGroupNodes, Map<String, List<JsonNode>> specificationNodesMap) {
        ArrayNode specificationGroupServiceArrayNode = getSpecificationGroupServiceArrayNode(serviceNode);
        for (JsonNode specGroupNode : specificationGroupNodes) {
            ObjectNode specGroupObjNode = (ObjectNode) specGroupNode;
            List<JsonNode> specificationNodes = specificationNodesMap.get(specGroupNode.get(AbstractSystemEntity.Fields.id).asText());
            if (specificationNodes != null) {
                specGroupObjNode.putArray(SpecificationGroup.Fields.systemModels).addAll(specificationNodes);
                specificationGroupServiceArrayNode.add(specGroupObjNode);
            }
        }
    }

    private static ArrayNode getSpecificationGroupServiceArrayNode(ObjectNode serviceNode) {
        ArrayNode specificationGroupServiceArrayNode = (ArrayNode) serviceNode.get(IntegrationSystem.Fields.specificationGroups);
        if (specificationGroupServiceArrayNode == null) {
            specificationGroupServiceArrayNode = serviceNode.putArray(IntegrationSystem.Fields.specificationGroups);
        }
        return specificationGroupServiceArrayNode;
    }

    private void setSpecificationSources(ObjectNode serviceNode, Map<String, List<File>> sourceFiles) throws IOException {
        ArrayNode specificationGroupServiceArrayNode = getSpecificationGroupServiceArrayNode(serviceNode);
        for (JsonNode specificationGroup : specificationGroupServiceArrayNode) {
            ArrayNode specificationsArrayNode = (ArrayNode) specificationGroup.get(SpecificationGroup.Fields.systemModels);
            if (specificationsArrayNode != null) {
                for (JsonNode specification : specificationsArrayNode) {
                    ArrayNode specificationSourcesArrayNode = (ArrayNode) specification.get(SystemModel.Fields.specificationSources);
                    if (specificationSourcesArrayNode != null) {
                        for (JsonNode specificationSource : specificationSourcesArrayNode) {
                            ObjectNode specificationSourceObjectNode = (ObjectNode) specificationSource;
                            String sourceFullPath = getNodeAsText(specificationSource.get(SPECIFICATION_SOURCE_FILE_NAME));
                            String fileName;
                            String parentDir;
                            if (!StringUtils.isBlank(sourceFullPath)) {
                                Path sourceFilePath = Paths.get(sourceFullPath);
                                parentDir = sourceFilePath.getParent().toString();
                                fileName = sourceFilePath.getFileName().toString();
                            } else {
                                // Fallback (old method of gathering source files)
                                OperationProtocol protocol = yamlMapper.treeToValue(
                                        serviceNode.get(IntegrationSystem.Fields.protocol), OperationProtocol.class);
                                fileName = ExportImportUtils.getSpecificationFileName(specificationSource, protocol);
                                parentDir = ExportImportUtils.generateDeprecatedSourceExportDir(specificationGroup,
                                        specification);
                            }

                            String finalFileName = fileName;
                            Optional<File> sourceFile = sourceFiles.computeIfAbsent(parentDir, k -> Collections.emptyList())
                                    .stream().filter(f -> f.getName().equals(finalFileName)).findAny();
                            if (sourceFile.isPresent()) {
                                specificationSourceObjectNode.put(
                                        SpecificationSource.Fields.source, getFileContent(sourceFile.get()));
                            }
                        }
                    }
                }
            }
        }
    }

    private ObjectNode migrateToActualFileVersion(ObjectNode serviceNode) throws Exception {

        if ((!serviceNode.has(IMPORT_VERSION_FIELD_OLD) && !serviceNode.has(IMPORT_MIGRATIONS_FIELD))
            ||
            (serviceNode.has(IMPORT_VERSION_FIELD_OLD) && serviceNode.get(IMPORT_VERSION_FIELD_OLD) != null
             &&
             serviceNode.has(IMPORT_MIGRATIONS_FIELD) && serviceNode.get(IMPORT_MIGRATIONS_FIELD) != null)
        ) {
            log.error(
                    "Incorrect combination of \"{}\" and \"{}\" fields for a service migration data",
                    IMPORT_VERSION_FIELD_OLD,
                    IMPORT_MIGRATIONS_FIELD);
            throw new Exception("Incorrect combination of fields for a service migration data");
        }

        List<Integer> importVersions;
        if (serviceNode.has(IMPORT_VERSION_FIELD_OLD)) {
            importVersions =
                    IntStream.rangeClosed(1, serviceNode.get(IMPORT_VERSION_FIELD_OLD).asInt())
                            .boxed()
                            .toList();
        } else {
            importVersions =
                    serviceNode.get(IMPORT_MIGRATIONS_FIELD) != null
                            ? Arrays.stream(
                                    serviceNode.get(IMPORT_MIGRATIONS_FIELD)
                                            .asText()
                                            .replaceAll("[\\[\\]]", "")
                                            .split(","))
                                .map(String::trim)
                                .filter(StringUtils::isNotEmpty)
                                .map(Integer::parseInt)
                                .toList()
                            : new ArrayList<>();
        }
        log.trace("importVersions = {}", importVersions);

        List<Integer> actualVersions = ImportFileMigrationUtils.getActualServiceFileMigrationVersions();
        log.trace("actualVersions = {}", actualVersions);

        List<Integer> nonexistentVersions = new ArrayList<>(importVersions);
        nonexistentVersions.removeAll(actualVersions);
        if (!nonexistentVersions.isEmpty()) {
            String serviceId = Optional.ofNullable(serviceNode.get("id")).map(JsonNode::asText).orElse(null);
            String serviceName = Optional.ofNullable(serviceNode.get("name")).map(JsonNode::asText).orElse(null);

            log.error(
                    "Unable to import the service {} ({}) exported from newer version: nonexistent migrations {} are present",
                    serviceName,
                    serviceId,
                    nonexistentVersions);

            throw new ServiceImportException(
                    serviceId,
                    serviceName,
                    "Unable to import a service exported from newer version");
        }

        List<Integer> versionsToMigrate = new ArrayList<>(actualVersions);
        versionsToMigrate.removeAll(importVersions);
        versionsToMigrate.sort(null);
        log.trace("versionsToMigrate = {}", versionsToMigrate);

        for (int version : versionsToMigrate) {
            serviceNode = importFileMigrations.get(version).makeMigration(serviceNode);
        }

        return serviceNode;
    }
}
