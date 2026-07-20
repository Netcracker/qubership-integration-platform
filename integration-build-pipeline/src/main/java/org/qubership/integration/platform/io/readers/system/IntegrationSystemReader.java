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
import org.qubership.integration.platform.chain.impl.ImportSpecificationGroupImpl;
import org.qubership.integration.platform.chain.impl.ImportSpecificationSourceImpl;
import org.qubership.integration.platform.chain.impl.ImportSystemImpl;
import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupDto;
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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

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
 * export instead embeds the groups in the system YAML, with their system models, operations, and
 * specification sources nested inside them and their fields sitting next to the identity rather than
 * under a {@code content} block. The reader descends into those groups as well, lifting each nested
 * model into the current shape before mapping it.
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
     * specification groups and system models from the sibling files there; for a legacy export it
     * reads the groups and their nested models embedded in the system YAML. Either way it loads each
     * specification source's text from the archive.
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
                JsonNode migratedSystemNode = yamlMapper.readTree(migratedYaml);
                model.setSpecificationGroups(readLegacyGroups(
                        model, migratedSystemNode, new Versions(originalSystemNode), archiveDirectory));
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
        return content != null && content.getSpecificationGroups() != null
                && !content.getSpecificationGroups().isEmpty();
    }

    /**
     * Walks the groups embedded in the system YAML, stamping each with the owning system id and
     * retaining only the ones that point at it.
     *
     * <p>The base mapping already produced a group per entry, but a legacy entry keeps
     * {@code synchronization} and {@code systemModels} beside the identity instead of under
     * {@code content}, so neither survives that mapping. Both are read here from the migrated YAML
     * node the entry came from. A group whose node cannot be located is dropped rather than imported
     * half-populated.
     */
    private List<ImportSpecificationGroup> readLegacyGroups(
            ImportSystem model,
            JsonNode migratedSystemNode,
            Versions versions,
            File archiveDirectory
    ) {
        JsonNode groupsArray = migratedSystemNode.path(CONTENT).path(SPECIFICATION_GROUPS_FIELD);
        List<ImportSpecificationGroup> groups = new ArrayList<>();
        for (ImportSpecificationGroup group : model.getSpecificationGroups()) {
            ImportSpecificationGroupImpl groupModel = (ImportSpecificationGroupImpl) group;
            if (model.getId() != null) {
                groupModel.setParentId(model.getId());
            }
            if (!Objects.equals(groupModel.getParentId(), model.getId())) {
                continue;
            }
            JsonNode groupNode = findGroupNode(groupsArray, groupModel.getId());
            if (groupNode.isMissingNode() || groupNode.isNull()) {
                continue;
            }
            applyLegacySynchronization(groupModel, groupNode);
            groupModel.setSystemModels(
                    readLegacyModels(groupNode, groupModel.getId(), versions, archiveDirectory));
            groups.add(groupModel);
        }
        return groups;
    }

    /**
     * Finds the YAML node an embedded group was mapped from. A single-group export writes the entry
     * as an object rather than a one-element array, so a non-array node is the group itself.
     */
    private static JsonNode findGroupNode(JsonNode groupsArray, String groupId) {
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

    private static void applyLegacySynchronization(ImportSpecificationGroupImpl group, JsonNode groupNode) {
        JsonNode synchronization = groupNode.path(CONTENT).path(SYNCHRONIZATION_FIELD);
        if (synchronization.isMissingNode() || synchronization.isNull()) {
            synchronization = groupNode.path(SYNCHRONIZATION_FIELD);
        }
        if (!synchronization.isMissingNode() && !synchronization.isNull()) {
            group.setSynchronization(synchronization.asBoolean());
        }
    }

    private List<ImportSystemModel> readLegacyModels(
            JsonNode groupNode,
            String groupId,
            Versions versions,
            File archiveDirectory
    ) {
        List<ImportSystemModel> systemModels = new ArrayList<>();
        JsonNode systemModelsArray = groupNode.path(SYSTEM_MODELS_FIELD);
        if (systemModelsArray.isMissingNode() || systemModelsArray.isNull()) {
            return systemModels;
        }
        if (!systemModelsArray.isArray()) {
            addLegacyModel(systemModels, systemModelsArray, groupId, versions, archiveDirectory);
            return systemModels;
        }
        for (JsonNode systemModelNode : systemModelsArray) {
            addLegacyModel(systemModels, systemModelNode, groupId, versions, archiveDirectory);
        }
        return systemModels;
    }

    private void addLegacyModel(
            List<ImportSystemModel> systemModels,
            JsonNode systemModelNode,
            String groupId,
            Versions versions,
            File archiveDirectory
    ) {
        if (systemModelNode.isMissingNode() || systemModelNode.isNull() || !systemModelNode.isObject()) {
            return;
        }
        systemModels.add(buildModel(
                prepareSystemModelNode(systemModelNode, groupId), versions, archiveDirectory));
    }

    /**
     * Lifts a legacy system-model node into the current shape: its fields move under {@code content},
     * the owning group is stamped as the parent, and the nested operations and specification sources
     * are carried across. Older exports keep those two lists beside the identity even when the rest
     * of the node already uses {@code content}, so they are merged in separately.
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
        JsonNode content = model.path(CONTENT);
        if (content instanceof ObjectNode contentObject
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
                        getFilesData(files, SPECIFICATION_GROUP_FILE_POSTFIX))
                .forEach(node -> {
                    ImportSpecificationGroup group = buildGroup(node, versions);
                    if (Objects.equals(group.getParentId(), systemId)) {
                        groups.add(group);
                    }
                });

        Stream.concat(
                        getFilesDataDeprecated(files, SPECIFICATION_FILE_PREFIX),
                        getFilesData(files, SPECIFICATION_FILE_POSTFIX))
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
            SpecificationGroupDto dto = yamlMapper.treeToValue(migratedNode, SpecificationGroupDto.class);
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
     * the file is missing.
     *
     * <p>Older exports leave the file name blank, so the source name and then its id stand in for
     * it. When the resolved name does not match a file directly, the lookup retries under the
     * resources subfolder, matching how the sources are laid out on export.
     */
    private String readSpecificationSource(ImportSpecificationSource source, File archiveDirectory) {
        String fileName = StringUtils.firstNonBlank(source.getFileName(), source.getName(), source.getId());
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

    private Stream<ObjectNode> getFilesData(Collection<File> files, String nameInfix) {
        return files.stream()
                .filter(file -> file.getName().contains(nameInfix)
                        && (file.getName().endsWith(".yaml") || file.getName().endsWith(".yml")))
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
