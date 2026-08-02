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
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ApiGroupDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.MigrationException;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.ExtractedSchemas;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.OperationKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;

@Slf4j
@Component
public class ServiceDeserializer {
    private final YAMLMapper yamlMapper;
    private final VersionsGetterService versionsGetterService;
    private final IntegrationSystemDtoMapper integrationSystemDtoMapper;
    private final ApiGroupDtoMapper apiGroupDtoMapper;
    private final SystemModelDtoMapper systemModelDtoMapper;
    private final FileMigrationService fileMigrationService;
    private final Collection<ServiceImportFileMigration> importFileMigrations;
    private final OperationSchemaExtractor operationSchemaExtractor;

    @Value("${app.prefix}")
    private String appName;

    @Autowired
    public ServiceDeserializer(
            YAMLMapper yamlExportImportMapper,
            VersionsGetterService versionsGetterService,
            IntegrationSystemDtoMapper integrationSystemDtoMapper,
            ApiGroupDtoMapper apiGroupDtoMapper,
            SystemModelDtoMapper systemModelDtoMapper,
            FileMigrationService fileMigrationService,
            Collection<ServiceImportFileMigration> importFileMigrations,
            OperationSchemaExtractor operationSchemaExtractor
    ) {
        this.yamlMapper = yamlExportImportMapper;
        this.versionsGetterService = versionsGetterService;
        this.integrationSystemDtoMapper = integrationSystemDtoMapper;
        this.apiGroupDtoMapper = apiGroupDtoMapper;
        this.systemModelDtoMapper = systemModelDtoMapper;
        this.fileMigrationService = fileMigrationService;
        this.importFileMigrations = importFileMigrations;
        this.operationSchemaExtractor = operationSchemaExtractor;
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
            ObjectNode migratedServiceNode = (ObjectNode) yamlMapper.readTree(serviceData);
            IntegrationSystemDto integrationSystemDto = yamlMapper.treeToValue(migratedServiceNode, IntegrationSystemDto.class);
            IntegrationSystem integrationSystem = integrationSystemDtoMapper.toInternalEntity(integrationSystemDto);

            Collection<File> files = listFiles(serviceDirectory);

            OperationProtocol protocol = integrationSystem.getProtocol();
            if (integrationSystemDto.getContent() != null && !integrationSystemDto.getContent().getApiGroups().isEmpty()) {
                processLegacyService(integrationSystem, versions, migratedServiceNode, serviceDirectory);
            } else {
                // Discovery accepts the deprecated flat prefix and both postfixes: the api-group format writes
                // `.api-group.<app>.yaml`, older archives `.specification-group.<app>.yaml`. Missing either would
                // import a service with no groups, silently.
                Stream.of(
                                getFilesDataDeprecated(files, SPECIFICATION_GROUP_FILE_PREFIX),
                                getFilesData(files, SPECIFICATION_GROUP_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX),
                                getFilesData(files, API_GROUP_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX))
                        .flatMap(Function.identity())
                        .forEach(node -> buildAndAddSpecificationGroup(node, versions, integrationSystem));

                // Discovery accepts both postfixes: the api format writes `.api.<app>.yaml`, older archives
                // `.specification.<app>.yaml`. Missing either would import a service with groups and no models, silently.
                Stream.of(
                                getFilesDataDeprecated(files, SPECIFICATION_FILE_PREFIX),
                                getFilesData(files, SPECIFICATION_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX),
                                getFilesData(files, API_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX))
                        .flatMap(Function.identity())
                        .forEach(node -> buildAndAddSpecification(
                                node, versions, protocol, integrationSystem.getApiGroups(), serviceDirectory));
            }

            return integrationSystem;
        } catch (ServiceImportException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Migrates every document except an api-format one, which is already current.
     * {@code content.specificationType} identifies that format: the field is required there and absent from every
     * earlier one. A specification group carries no {@code specificationType} in any format, so its nodes always
     * reach {@code migrate()}. That is correct, because the version subtraction there leaves nothing to apply and
     * {@code ApiGroupDto} ignores the injected {@code migrations} field.
     */
    private ObjectNode migrateIfNeeded(ObjectNode node, Collection<Integer> versions) throws MigrationException {
        return node.path(CONTENT).hasNonNull(SPECIFICATION_TYPE) ? node : migrate(node, versions);
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

    @Deprecated
    private Stream<ObjectNode> getFilesDataDeprecated(Collection<File> files, String namePrefix) {
        return files.stream()
                .filter(file -> file.getName().startsWith(namePrefix))
                .map(getFileObjectNode());
    }

    private Stream<ObjectNode> getFilesData(Collection<File> files, String namePostfix) {
        return files.stream()
                .filter(file -> file.getName().endsWith(namePostfix))
                .map(getFileObjectNode());
    }

    private @NotNull Function<File, ObjectNode> getFileObjectNode() {
        return file -> {
            try {
                return requireObjectNode(yamlMapper.readTree(file), file.getName());
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        };
    }

    private static ObjectNode requireObjectNode(JsonNode node, String description) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(
                    "Expected object node but got " + node.getNodeType().name() + " in " + description);
        }
        return (ObjectNode) node;
    }

    private void processLegacyService(
            IntegrationSystem integrationSystem,
            Collection<Integer> versions,
            ObjectNode migratedServiceNode,
            File serviceDirectory
    ) {
        OperationProtocol protocol = integrationSystem.getProtocol();
        for (JsonNode groupNode : inlineGroupsOf(migratedServiceNode)) {
            processSpecificationGroup(groupNode, integrationSystem, versions, serviceDirectory, protocol);
        }
    }

    /**
     * The inline group list under either name: the one V104 renames, or the one it renames to. The DTO reads both, so
     * this loop has to as well, or a document that took the legacy branch on the old name imports zero groups.
     */
    private static JsonNode inlineGroupsOf(ObjectNode serviceNode) {
        JsonNode content = serviceNode.path(CONTENT);
        JsonNode groups = content.path(API_GROUPS);
        return groups.isEmpty() ? content.path(SPECIFICATION_GROUPS) : groups;
    }

    /**
     * Imports one specification group inlined in a legacy service file.
     * <p>
     * The group node is migrated from the shape it has in the file, before anything is written into it. The
     * service-level migration only relocates the group array itself, so each group inside still carries the shape of
     * the version the file was exported from, and {@code versions} describes exactly that shape. Patching the node
     * first — or deriving it from an already-parsed DTO — would hand a current-shape node to a pre-V101 version list,
     * and V101 would wrap it a second time into {@code content.content}.
     */
    private void processSpecificationGroup(
            JsonNode groupNode,
            IntegrationSystem integrationSystem,
            Collection<Integer> versions,
            File serviceDirectory,
            OperationProtocol protocol
    ) {
        ObjectNode rawGroupNode = requireObjectNode(groupNode, "inline specification group").deepCopy();
        // before V101 the group fields sit at the root, after it they live under content
        JsonNode systemModels = rawGroupNode.has(SYSTEM_MODELS)
                ? rawGroupNode.path(SYSTEM_MODELS)
                : rawGroupNode.path(CONTENT).path(SYSTEM_MODELS);
        JsonNode synchronization = rawGroupNode.path(SYNCHRONIZATION);

        ObjectNode migratedGroupNode = migrateSpecificationGroupNode(rawGroupNode, versions);
        ObjectNode content = contentNodeOf(migratedGroupNode);
        if (integrationSystem.getId() != null) {
            content.put(PARENT_ID, integrationSystem.getId());
        }
        if (!synchronization.isMissingNode() && !synchronization.isNull()) {
            content.put(SYNCHRONIZATION, synchronization.asBoolean());
        }
        addSpecificationGroup(migratedGroupNode, integrationSystem);

        processSystemModels(
                systemModels, migratedGroupNode.path("id").asText(null), integrationSystem, versions,
                serviceDirectory, protocol);
    }

    private void processSystemModels(
            JsonNode systemModelsList,
            String groupId,
            IntegrationSystem integrationSystem,
            Collection<Integer> versions,
            File serviceDirectory,
            OperationProtocol protocol
    ) {
        for (JsonNode model : systemModelsList) {
            if (model.isNull()) {
                log.warn("Skipping an empty system model entry of specification group {}", groupId);
                continue;
            }
            ObjectNode migratedModel = migrateSpecificationNode(
                    requireObjectNode(model, "inline system model").deepCopy(), versions, protocol);
            ensureModelContentParentId(migratedModel, groupId);
            addSpecification(migratedModel, integrationSystem.getApiGroups(), serviceDirectory, protocol);
        }
    }

    private void ensureModelContentParentId(ObjectNode model, String groupId) {
        ObjectNode content = contentNodeOf(model);
        if (!content.hasNonNull(PARENT_ID)) {
            content.put(PARENT_ID, groupId);
        }
    }

    private ObjectNode contentNodeOf(ObjectNode node) {
        JsonNode content = node.path(CONTENT);
        if (content.isObject()) {
            return (ObjectNode) content;
        }
        ObjectNode created = yamlMapper.createObjectNode();
        node.set(CONTENT, created);
        return created;
    }

    private ObjectNode migrateSpecificationGroupNode(ObjectNode node, Collection<Integer> versions) {
        try {
            return migrateIfNeeded(node, versions);
        } catch (MigrationException exception) {
            throw new RuntimeException("Failed to migrate specification group data", exception);
        }
    }

    /**
     * The one wrapper both model routes share. It stamps the protocol on the node before migrating, so V103 can type
     * the operations; the scratch field rides through the pipeline the way {@code migrate()} carries {@code migrations}.
     */
    private ObjectNode migrateSpecificationNode(ObjectNode node, Collection<Integer> versions, OperationProtocol protocol) {
        if (protocol != null) {
            node.set(MIGRATION_PROTOCOL, TextNode.valueOf(protocol.name()));
        }
        try {
            return migrateIfNeeded(node, versions);
        } catch (MigrationException exception) {
            throw new RuntimeException("Failed to migrate specification data", exception);
        }
    }

    private void buildAndAddSpecificationGroup(
            ObjectNode node,
            Collection<Integer> versions,
            IntegrationSystem integrationSystem
    ) {
        addSpecificationGroup(migrateSpecificationGroupNode(node, versions), integrationSystem);
    }

    private void addSpecificationGroup(ObjectNode migratedNode, IntegrationSystem integrationSystem) {
        try {
            ApiGroupDto specificationGroupDto = yamlMapper.treeToValue(migratedNode, ApiGroupDto.class);
            ApiGroup specificationGroup = apiGroupDtoMapper.toInternalEntity(specificationGroupDto);

            String parentId = specificationGroupDto.getContent() == null
                    ? null
                    : specificationGroupDto.getContent().getParentId();
            if (Objects.equals(parentId, integrationSystem.getId())) {
                integrationSystem.addApiGroup(specificationGroup);
            }
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Failed to construct specification group from YAML", exception);
        }
    }

    private void buildAndAddSpecification(
            ObjectNode node,
            Collection<Integer> versions,
            OperationProtocol protocol,
            Collection<ApiGroup> specificationGroups,
            File resourceDirectory
    ) {
        addSpecification(
                migrateSpecificationNode(node, versions, protocol), specificationGroups, resourceDirectory, protocol);
    }

    private void addSpecification(
            ObjectNode migratedNode,
            Collection<ApiGroup> specificationGroups,
            File resourceDirectory,
            OperationProtocol protocol
    ) {
        try {
            SystemModelDto systemModelDto = yamlMapper.treeToValue(migratedNode, SystemModelDto.class);
            SystemModel systemModel = systemModelDtoMapper.toInternalEntity(systemModelDto);
            specificationGroups.stream()
                    .filter(group -> Objects.equals(group.getId(), systemModelDto.getContent().getParentId()))
                    .findFirst()
                    .ifPresent(group -> group.addSystemModel(systemModel));
            var sourceDtos = systemModelDto.getContent().getSpecificationSources();
            List<String> missingFiles = new ArrayList<>();
            for (var specificationSourceDto : sourceDtos) {
                var specificationSourceBuilder = SpecificationSource.builder();
                specificationSourceBuilder
                        .id(specificationSourceDto.getId())
                        .name(specificationSourceDto.getName())
                        .description(specificationSourceDto.getDescription())
                        .createdBy(specificationSourceDto.getCreatedBy())
                        .createdWhen(specificationSourceDto.getCreatedWhen())
                        .modifiedBy(specificationSourceDto.getModifiedBy())
                        .modifiedWhen(specificationSourceDto.getModifiedWhen())
                        .isMainSource(specificationSourceDto.isMainSource());
                // No sourceHash from the file: the source builder below recomputes it, and a missing source must
                // leave it null rather than record a hash for content that is not there.
                Path resourceRoot = resourceDirectory.toPath().normalize();
                Path sourcePath = resourceRoot.resolve(specificationSourceDto.getFileName()).normalize();
                if (!Files.exists(sourcePath) && !specificationSourceDto.getFileName().contains(RESOURCES_FOLDER_PREFIX)) {
                    sourcePath = resourceRoot.resolve(RESOURCES_FOLDER_PREFIX + specificationSourceDto.getFileName()).normalize();
                }
                // Reject a fileName that escapes the resource directory (absolute path or `..`): the fileName comes
                // straight from the imported archive, so an unchecked resolve()+readString would disclose arbitrary
                // server files. An escaping source is treated as missing, mirroring the delete-side guard.
                if (Files.exists(sourcePath) && sourcePath.startsWith(resourceRoot)) {
                    try {
                        specificationSourceBuilder.source(Files.readString(sourcePath));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read specification source", e);
                    }
                } else {
                    missingFiles.add(specificationSourceDto.getFileName());
                }
                systemModel.addProvidedSpecificationSource(specificationSourceBuilder.build());
            }
            reportMissingSources(systemModel, sourceDtos.size(), missingFiles);
            fillMissingOperationSpecifications(systemModel, protocol);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Failed to construct specification from YAML", exception);
        }
    }

    /**
     * Repopulates the operation {@code specification} column, which the api format no longer carries in the file. The
     * value is re-derived from the raw source the archive ships, so the async MaaS classifier the resolvers store there
     * survives the round trip.
     *
     * <p>An operation that arrived with its own value keeps it: legacy files still carry the field, and the file is
     * authoritative over anything re-derived. A protocol with no schema extraction, a model with no source and a parse
     * failure all leave the column null rather than failing the import.
     */
    private void fillMissingOperationSpecifications(SystemModel systemModel, OperationProtocol protocol) {
        List<Operation> operations = systemModel.getOperations();
        List<SpecificationSource> sources = systemModel.getSpecificationSources();
        if (protocol == null || operations == null || sources == null || sources.isEmpty()
                || operations.stream().allMatch(operation -> operation.getSpecification() != null)) {
            return;
        }
        Map<OperationKey, ExtractedSchemas> schemasByOperation;
        try {
            // withSchemas = false: only the specification slice is read here, and materializing request and response
            // schemas inlines every referenced component into every operation, inside the import transaction.
            schemasByOperation = operationSchemaExtractor.extractAll(sources, protocol, false);
        } catch (RuntimeException exception) {
            // The parsers wrap everything as SpecificationImportException with one fixed message, so only the cause
            // says what actually broke.
            log.warn("Cannot derive operation specifications for imported model {}", systemModel.getId(), exception);
            return;
        }
        List<OperationKey> unmatched = new ArrayList<>();
        int missing = 0;
        for (Operation operation : operations) {
            if (operation.getSpecification() != null) {
                continue;
            }
            missing++;
            OperationKey key = OperationKey.of(operation.getPath(), operation.getMethod());
            ExtractedSchemas schemas = schemasByOperation.get(key);
            if (schemas == null || schemas.specification() == null) {
                unmatched.add(key);
                continue;
            }
            operation.setSpecification(schemas.specification());
        }
        if (!unmatched.isEmpty()) {
            log.warn("Import of specification {}: {} of {} operations did not match the parsed source"
                            + " and keep a null specification. Unmatched operations: {}",
                    systemModel.getId(), unmatched.size(), missing, OperationSchemaExtractor.describeKeys(unmatched));
        }
    }

    /**
     * A missing file among several sources is a warning. A model whose every declared source is missing has nothing to
     * import and would export an empty {@code specifications} list that the api schema rejects, so it fails the import.
     */
    private void reportMissingSources(SystemModel systemModel, int declaredCount, List<String> missingFiles) {
        if (missingFiles.isEmpty()) {
            return;
        }
        if (missingFiles.size() == declaredCount) {
            throw new ServiceImportException(systemModel.getId(), systemModel.getName(),
                    ("Specification model %s declares %d source file(s), but none was found on disk. The model has no "
                            + "source to import and cannot produce a valid api export. Restore the missing source files "
                            + "or remove the model, then re-import.").formatted(systemModel.getId(), declaredCount));
        }
        missingFiles.forEach(fileName -> log.warn("Specification source file not found: {}", fileName));
    }
}
