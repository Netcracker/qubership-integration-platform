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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.chain.impl.ImportSpecificationSourceImpl;
import org.qubership.integration.platform.chain.impl.ImportSystemImpl;
import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.ApiGroupContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.ApiGroupDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.ImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.MigrationException;
import org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.API_FILE_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.API_GROUP_FILE_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.PARENT_ID;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.RESOURCES_FOLDER_PREFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_FILE_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_FILE_PREFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_GROUP_FILE_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_GROUP_FILE_PREFIX;

/**
 * Reads an integration-system export into the library {@link ImportSystem} model.
 *
 * <p>The reader owns the whole export, mirroring the chain-side {@code ChainReader}: it migrates the
 * system YAML to the current file version, deserializes it, and assembles the complete graph of
 * specification groups, system models, operations, and specification sources. The catalog turns the
 * finished model into its JPA entities in a later step and no longer walks the archive itself.
 *
 * <p>A modern export keeps each specification group and system model in its own file that shares the
 * archive directory with the system YAML; the reader walks that directory to collect them. A legacy
 * export instead embeds the groups in the system YAML. The reader maps each embedded group, stamps
 * it with its parent system, and descends into the system models the group carries inline, so a
 * legacy archive imports the same graph a modern one does.
 */
@Slf4j
@Component
public class IntegrationSystemReader {

    private static final String SPECIFICATION_GROUPS_FIELD = "specificationGroups";
    private static final String SYSTEM_MODELS_FIELD = "systemModels";
    private static final String SYNCHRONIZATION_FIELD = "synchronization";
    private static final String OPERATIONS_FIELD = "operations";
    private static final String SPECIFICATION_SOURCES_FIELD = "specificationSources";

    private final YAMLMapper yamlMapper;
    private final FileMigrationService fileMigrationService;
    private final VersionsGetterService versionsGetterService;
    private final Collection<ServiceImportFileMigration> serviceImportFileMigrations;

    public IntegrationSystemReader(
            @Qualifier("defaultYamlMapper") YAMLMapper yamlMapper,
            FileMigrationService fileMigrationService,
            VersionsGetterService versionsGetterService,
            Collection<ServiceImportFileMigration> serviceImportFileMigrations
    ) {
        this.yamlMapper = yamlMapper;
        this.fileMigrationService = fileMigrationService;
        this.versionsGetterService = versionsGetterService;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
    }

    /**
     * Reads the integration system rooted at {@code systemFile}.
     *
     * <p>The archive directory is the file's parent. For a modern export the reader collects the
     * specification groups and system models from the sibling files there and loads each
     * specification source's text; for a legacy export it maps the groups embedded in the system
     * YAML.
     *
     * @param systemFile the exported integration-system YAML file
     * @throws IllegalArgumentException if the file cannot be read or migrated
     */
    public ImportSystem read(File systemFile) {
        try {
            File archiveDirectory = systemFile.getParentFile();
            JsonNode originalSystemNode = yamlMapper.readTree(systemFile);
            String migratedYaml = migrateToCurrentFileVersion(Files.readString(systemFile.toPath()));
            IntegrationSystemDto dto = yamlMapper.readValue(migratedYaml, IntegrationSystemDto.class);

            ImportSystemImpl model = (ImportSystemImpl) toModel(dto);

            if (hasEmbeddedGroups(dto)) {
                ObjectNode migratedSystemNode = (ObjectNode) yamlMapper.readTree(migratedYaml);
                model.setSpecificationGroups(readLegacyGroups(
                        model, dto, migratedSystemNode, new Versions(originalSystemNode), archiveDirectory));
            } else {
                model.setSpecificationGroups(
                        readSeparateGroupsAndModels(archiveDirectory, model.getId(), originalSystemNode));
            }
            return model;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to read integration system from file " + systemFile.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Maps an already deserialized integration-system export to the library model. Package-visible
     * so tests can exercise the mapping without a file on disk. The mapped model carries only the
     * system-level fields and, for a legacy export, the embedded specification groups; the reader
     * fills in the separate-file groups and the specification-source text.
     */
    ImportSystem toModel(IntegrationSystemDto dto) {
        return SystemImportModelMapper.toModel(dto);
    }

    private static boolean hasEmbeddedGroups(IntegrationSystemDto dto) {
        IntegrationSystemContentDto content = dto.getContent();
        return content != null && content.getApiGroups() != null
                && !content.getApiGroups().isEmpty();
    }

    /**
     * Reads the specification groups a legacy export embeds in the system YAML.
     *
     * <p>Each group is stamped with the owning system id and kept only when it points at that
     * system, matching the separate-file path. The raw YAML node of the group carries the inline
     * system models, which the mapped DTO drops, so the models are read from that node.
     */
    private List<ImportSpecificationGroup> readLegacyGroups(
            ImportSystem model,
            IntegrationSystemDto dto,
            ObjectNode migratedSystemNode,
            Versions versions,
            File archiveDirectory
    ) {
        List<ImportSpecificationGroup> groups = new ArrayList<>();
        List<ApiGroupDto> groupDtos = dto.getContent().getApiGroups();
        JsonNode groupsArray = migratedSystemNode.path(CONTENT).path(SPECIFICATION_GROUPS_FIELD);
        for (ApiGroupDto groupDto : groupDtos) {
            JsonNode groupNode = findSpecificationGroup(groupsArray, groupDto.getId());
            if (groupNode.isMissingNode() || groupNode.isNull()) {
                continue;
            }
            setGroupParentId(groupDto, model.getId());
            applySynchronization(groupDto, groupNode);

            ImportSpecificationGroup group = buildGroup((ObjectNode) yamlMapper.valueToTree(groupDto), versions);
            if (!Objects.equals(group.getParentId(), model.getId())) {
                continue;
            }
            groups.add(group);

            JsonNode systemModelsArray = groupNode.path(SYSTEM_MODELS_FIELD);
            if (systemModelsArray.isMissingNode() || systemModelsArray.isNull()) {
                continue;
            }
            readLegacySystemModels(systemModelsArray, groupDto.getId(), versions, archiveDirectory, groups);
        }
        return groups;
    }

    /**
     * Finds the raw node of one embedded group by id. A single embedded group may be written as a
     * lone object rather than a one-element array, which is returned as-is.
     */
    private static JsonNode findSpecificationGroup(JsonNode groupsArray, String groupId) {
        if (!groupsArray.isArray()) {
            return groupsArray;
        }
        for (JsonNode node : groupsArray) {
            if (Objects.equals(groupId, node.path("id").textValue())) {
                return node;
            }
        }
        return MissingNode.getInstance();
    }

    private static void setGroupParentId(ApiGroupDto group, String systemId) {
        if (group.getContent() == null) {
            group.setContent(ApiGroupContentDto.builder().build());
        }
        if (systemId != null) {
            group.getContent().setParentId(systemId);
        }
    }

    /**
     * Carries the synchronization flag over from the raw node, which holds it either under
     * {@code content} or, in the oldest layout, directly on the group.
     */
    private static void applySynchronization(ApiGroupDto group, JsonNode groupNode) {
        JsonNode synchronization = groupNode.path(CONTENT).path(SYNCHRONIZATION_FIELD);
        if (synchronization.isMissingNode() || synchronization.isNull()) {
            synchronization = groupNode.path(SYNCHRONIZATION_FIELD);
        }
        if (!synchronization.isMissingNode() && !synchronization.isNull()) {
            group.getContent().setSynchronization(synchronization.asBoolean());
        }
    }

    private void readLegacySystemModels(
            JsonNode systemModelsArray,
            String groupId,
            Versions versions,
            File archiveDirectory,
            List<ImportSpecificationGroup> groups
    ) {
        if (!systemModelsArray.isArray()) {
            readLegacySystemModel(systemModelsArray, groupId, versions, archiveDirectory, groups);
            return;
        }
        for (JsonNode systemModelNode : systemModelsArray) {
            readLegacySystemModel(systemModelNode, groupId, versions, archiveDirectory, groups);
        }
    }

    private void readLegacySystemModel(
            JsonNode systemModelNode,
            String groupId,
            Versions versions,
            File archiveDirectory,
            List<ImportSpecificationGroup> groups
    ) {
        if (systemModelNode.isMissingNode() || systemModelNode.isNull() || !systemModelNode.isObject()) {
            return;
        }
        ImportSystemModel systemModel =
                buildModel(prepareSystemModelNode(systemModelNode, groupId), versions, archiveDirectory);
        groups.stream()
                .filter(group -> Objects.equals(group.getId(), systemModel.getParentId()))
                .findFirst()
                .ifPresent(group -> group.getSystemModels().add(systemModel));
    }

    /**
     * Normalizes one inline system model to the shape {@link #buildModel} expects: fields under
     * {@code content}, a parent id pointing at the owning group, and the operations and
     * specification sources the oldest layout keeps beside {@code content} rather than inside it.
     */
    private ObjectNode prepareSystemModelNode(JsonNode systemModelNode, String groupId) {
        ObjectNode result = systemModelNode.has(CONTENT)
                ? (ObjectNode) systemModelNode.deepCopy()
                : MigrationUtil.moveFieldsToContentField((ObjectNode) systemModelNode);
        ensureModelContentParentId(result, groupId);
        mergeLegacyFieldsIntoContent(result, systemModelNode, OPERATIONS_FIELD);
        mergeLegacyFieldsIntoContent(result, systemModelNode, SPECIFICATION_SOURCES_FIELD);
        return result;
    }

    private static void mergeLegacyFieldsIntoContent(ObjectNode model, JsonNode source, String fieldName) {
        JsonNode legacyField = source.path(fieldName);
        if (!legacyField.isArray() || legacyField.isEmpty()) {
            return;
        }
        if (model.path(CONTENT) instanceof ObjectNode contentObject
                && (!contentObject.has(fieldName) || contentObject.get(fieldName).isEmpty())) {
            contentObject.set(fieldName, legacyField);
        }
    }

    private void ensureModelContentParentId(ObjectNode model, String groupId) {
        JsonNode contentModel = model.path(CONTENT);
        if (contentModel.isMissingNode() || contentModel.isNull() || !contentModel.isObject()) {
            ObjectNode contentNode = yamlMapper.createObjectNode();
            contentNode.put(PARENT_ID, groupId);
            model.set(CONTENT, contentNode);
        } else if (contentModel.path(PARENT_ID).isMissingNode() || contentModel.path(PARENT_ID).isNull()) {
            ((ObjectNode) contentModel).put(PARENT_ID, groupId);
        }
    }

    private List<ImportSpecificationGroup> readSeparateGroupsAndModels(
            File archiveDirectory,
            String systemId,
            JsonNode originalSystemNode
    ) {
        Collection<File> files = listFiles(archiveDirectory);
        Versions versions = new Versions(originalSystemNode);

        List<ImportSpecificationGroup> groups = new ArrayList<>();
        Stream.concat(
                        getFilesDataDeprecated(files, SPECIFICATION_GROUP_FILE_PREFIX),
                        getFilesData(files, API_GROUP_FILE_POSTFIX, SPECIFICATION_GROUP_FILE_POSTFIX))
                .forEach(node -> {
                    ImportSpecificationGroup group = buildGroup(node, versions);
                    if (Objects.equals(group.getParentId(), systemId)) {
                        groups.add(group);
                    }
                });

        Stream.concat(
                        getFilesDataDeprecated(files, SPECIFICATION_FILE_PREFIX),
                        getFilesData(files, API_FILE_POSTFIX, SPECIFICATION_FILE_POSTFIX))
                .forEach(node -> {
                    ImportSystemModel systemModel = buildModel(node, versions, archiveDirectory);
                    groups.stream()
                            .filter(group -> Objects.equals(group.getId(), systemModel.getParentId()))
                            .findFirst()
                            .ifPresent(group -> group.getSystemModels().add(systemModel));
                });
        return groups;
    }

    private ImportSpecificationGroup buildGroup(ObjectNode node, Versions versions) {
        try {
            ObjectNode migratedNode = node.has(CONTENT) ? node : migrate(node, versions.get());
            ApiGroupDto dto = yamlMapper.treeToValue(migratedNode, ApiGroupDto.class);
            return SystemImportModelMapper.toModel(dto);
        } catch (MigrationException exception) {
            throw new RuntimeException("Failed to migrate specification group data", exception);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to construct specification group from YAML", exception);
        }
    }

    private ImportSystemModel buildModel(ObjectNode node, Versions versions, File archiveDirectory) {
        try {
            ObjectNode migratedNode = node.has(CONTENT) ? node : migrate(node, versions.get());
            SystemModelDto dto = yamlMapper.treeToValue(migratedNode, SystemModelDto.class);
            ImportSystemModel systemModel = SystemImportModelMapper.toModel(dto);
            for (ImportSpecificationSource source : systemModel.getSpecificationSources()) {
                ((ImportSpecificationSourceImpl) source)
                        .setSource(readSpecificationSource(source, archiveDirectory));
            }
            return systemModel;
        } catch (MigrationException exception) {
            throw new RuntimeException("Failed to migrate specification data", exception);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to construct specification from YAML", exception);
        }
    }

    private ObjectNode migrate(ObjectNode node, Collection<Integer> versions) throws MigrationException {
        node.set("migrations", TextNode.valueOf(versions.stream().sorted().toList().toString()));
        return fileMigrationService.migrate(
                node,
                serviceImportFileMigrations.stream().map(ImportFileMigration.class::cast).toList());
    }

    /**
     * Reads a specification source's text from the archive directory, or returns {@code null} when
     * the file is missing. Falls back to the resources subfolder when the recorded file name does
     * not resolve directly, matching how the sources are laid out on export.
     *
     * <p>Older archives omit the file name, so the lookup falls back to the source's name and then
     * its id.
     */
    private String readSpecificationSource(ImportSpecificationSource source, File archiveDirectory) {
        String fileName = extractSpecSourceFileName(source);
        Path sourcePath = archiveDirectory.toPath().resolve(fileName);
        if (!Files.exists(sourcePath) && !fileName.contains(RESOURCES_FOLDER_PREFIX)) {
            sourcePath = archiveDirectory.toPath().resolve(RESOURCES_FOLDER_PREFIX + fileName);
        }
        if (!Files.exists(sourcePath)) {
            log.warn("Specification source file not found: {}", fileName);
            return null;
        }
        try {
            return Files.readString(sourcePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read specification source", e);
        }
    }

    private static String extractSpecSourceFileName(ImportSpecificationSource source) {
        return StringUtils.firstNonBlank(source.getFileName(), source.getName(), source.getId());
    }

    private static Collection<File> listFiles(File archiveDirectory) {
        try (Stream<Path> fs = Files.walk(archiveDirectory.toPath())) {
            return fs.filter(Files::isRegularFile)
                    .map(Path::toFile).toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list service directory", e);
        }
    }

    @Deprecated
    private Stream<ObjectNode> getFilesDataDeprecated(Collection<File> files, String namePrefix) {
        return files.stream()
                .filter(file -> file.getName().startsWith(namePrefix))
                .map(getFileObjectNode());
    }

    // The first infix is the generation the exporter writes; the rest are older names that stay readable.
    private Stream<ObjectNode> getFilesData(Collection<File> files, String... nameInfixes) {
        return files.stream()
                .filter(file -> (file.getName().endsWith(".yaml") || file.getName().endsWith(".yml"))
                        && Arrays.stream(nameInfixes).anyMatch(infix -> file.getName().contains(infix)))
                .map(getFileObjectNode());
    }

    private Function<File, ObjectNode> getFileObjectNode() {
        return file -> {
            try {
                JsonNode node = yamlMapper.readTree(file);
                if (!node.isObject()) {
                    throw new RuntimeException("Expected object node but got " + node.getNodeType().name());
                }
                return (ObjectNode) node;
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        };
    }

    private String migrateToCurrentFileVersion(String systemYaml) throws MigrationException {
        List<ImportFileMigration> migrations = serviceImportFileMigrations.stream()
                .map(ImportFileMigration.class::cast)
                .toList();
        return fileMigrationService.migrate(systemYaml, migrations);
    }

    /**
     * The migration versions of the system YAML, read once and reused for every separate file that
     * still needs migrating. A version-less file that carries no separate specification files never
     * queries this, so the read does not fail on it.
     */
    private final class Versions {
        private final JsonNode systemNode;
        private Collection<Integer> versions;

        private Versions(JsonNode systemNode) {
            this.systemNode = systemNode;
        }

        private Collection<Integer> get() {
            if (versions == null) {
                try {
                    versions = versionsGetterService.getVersions(systemNode);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to read migration versions from the system file", e);
                }
            }
            return versions;
        }
    }
}
