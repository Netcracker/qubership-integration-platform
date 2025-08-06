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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServicesNotFoundException;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.SystemDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote.SystemCompareAction;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportMode;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.system.SystemsCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ContextBaseService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ContextServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ContextServiceSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.ZIP_EXTENSION;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportUtils.*;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportUtils.extractSystemIdFromFileName;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;


@Service
@Slf4j
@Transactional
public class ContextExportImportService {

    private final TransactionTemplate transactionTemplate;
    private final YAMLMapper yamlMapper;

    private final ContextBaseService contextBaseService;

    protected final ActionsLogService actionLogger;
    private final ContextServiceSerializer contextServiceSerializer;
    private final ContextServiceDeserializer contextServiceDeserializer;
    private final ImportSessionService importProgressService;
    private final ImportInstructionsService importInstructionsService;


    @Autowired
    public ContextExportImportService(
            TransactionTemplate transactionTemplate,
            ContextBaseService contextBaseService,
            YAMLMapper yamlExportImportMapper,
            ActionsLogService actionLogger,
            ContextServiceSerializer contextServiceSerializer, ServiceDeserializer serviceDeserializer,
            ContextServiceDeserializer contextServiceDeserializer, ImportSessionService importProgressService,
            ImportInstructionsService importInstructionsService
    ) {
        this.transactionTemplate = transactionTemplate;
        this.contextBaseService = contextBaseService;
        this.yamlMapper = yamlExportImportMapper;
        this.actionLogger = actionLogger;
        this.contextServiceSerializer = contextServiceSerializer;
        this.contextServiceDeserializer = contextServiceDeserializer;
        this.importProgressService = importProgressService;
        this.importInstructionsService = importInstructionsService;
    }


    private ExportedSystemObject exportOneSystem(ContextSystem system) {
        try {
            ExportedSystemObject exportedSystem;
            if (system != null) {
                exportedSystem = contextServiceSerializer.serialize(system);
            } else {
                throw new IllegalArgumentException("Unsupported system type");
            }

            return exportedSystem;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            String systemId = system != null && system.getId() != null ? "with system id: " + system.getId() + " " : "";
            String errMessage = "Error while serializing system " + systemId + e.getMessage();
            log.error(errMessage);
            throw new RuntimeException(errMessage, e);
        }
    }

    private List<ExportedSystemObject> exportSystems(List<ContextSystem> systems) {
        return systems.stream().map(this::exportOneSystem).collect(Collectors.toList());
    }

    public byte[] exportSystemsRequest(List<String> systemIds) {
        List<ContextSystem> systems = new ArrayList<>();
        if (systemIds == null) {
            systems.addAll(contextBaseService.getAll());
        } else {
            systems.addAll(systemIds.stream().map(contextBaseService::getByIdOrNull).filter(Objects::nonNull)
                    .toList());
        }
        if (systems.isEmpty()) {
            return null;
        }

        List<ExportedSystemObject> exportedSystems = exportSystems(systems);
        byte[] archive = contextServiceSerializer.writeSerializedArchive(exportedSystems);
        for (ContextSystem system : systems) {
            logSystemExportImport(system, null, LogOperation.EXPORT);
        }

        return archive;
    }

    public List<ImportSystemResult> getSystemsImportPreviewRequest(MultipartFile file) {
        List<ImportSystemResult> response = new ArrayList<>();
        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (ZIP_EXTENSION.equalsIgnoreCase(fileExtension)) {
            String exportDirectory = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(),
                    UUID.randomUUID().toString()).toString();
            List<File> extractedSystemFiles = new ArrayList<>();

            try (InputStream fs = file.getInputStream()) {
                extractedSystemFiles = extractSystemsFromZip(fs, exportDirectory);
            } catch (ServicesNotFoundException e) {
                deleteFile(exportDirectory);
            } catch (IOException e) {
                deleteFile(exportDirectory);
                throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                deleteFile(exportDirectory);
                throw e;
            }

            ImportInstructionsConfig instructionsConfig = importInstructionsService
                    .getServiceImportInstructionsConfig(Set.of(ImportInstructionAction.IGNORE));
            for (File singleSystemFile : extractedSystemFiles) {
                response.add(getSystemChanges(singleSystemFile, instructionsConfig));
            }
            deleteFile(exportDirectory);
        } else {
            throw new RuntimeException("Unsupported file extension: " + fileExtension);
        }

        return response;
    }

    protected ImportSystemResult getSystemChanges(File mainSystemFile, ImportInstructionsConfig instructionsConfig) {
        ImportSystemResult resultSystemCompareDTO;

        String systemId = null;
        String systemName = null;

        try {
            ObjectNode serviceNode = getFileNode(mainSystemFile);
            SystemDeserializationResult deserializationResult = getBaseSystemDeserializationResult(serviceNode);
            IntegrationSystem baseSystem = deserializationResult.getSystem();
            systemId = baseSystem.getId();
            systemName = baseSystem.getName();
            Long systemModifiedWhen = baseSystem.getModifiedWhen() != null ? baseSystem.getModifiedWhen().getTime() : 0;
            ImportInstructionAction instructionAction = instructionsConfig.getIgnore().contains(systemId)
                    ? ImportInstructionAction.IGNORE
                    : null;

            resultSystemCompareDTO = ImportSystemResult.builder()
                    .id(systemId)
                    .modified(systemModifiedWhen)
                    .instructionAction(instructionAction)
                    .build();
        } catch (RuntimeException | IOException e) {
            log.error("Exception while system compare: ", e);
            resultSystemCompareDTO = ImportSystemResult.builder()
                    .id(systemId)
                    .name(systemName)
                    .requiredAction(SystemCompareAction.ERROR)
                    .message("Exception while system compare: " + e.getMessage())
                    .build();
        }
        return resultSystemCompareDTO;
    }

    @Transactional(propagation = NOT_SUPPORTED)
    public List<ImportSystemResult> importContextSystemRequest(MultipartFile importFile, List<String> systemIds) {
        List<ImportSystemResult> response = new ArrayList<>();
        String fileExtension = FilenameUtils.getExtension(importFile.getOriginalFilename());
        logSystemExportImport(null, importFile.getOriginalFilename(), LogOperation.IMPORT);
        if (ZIP_EXTENSION.equalsIgnoreCase(fileExtension)) {
            String exportDirectory = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(),
                    UUID.randomUUID().toString()).toString();
            List<File> extractedSystemFiles;

            try (InputStream fs = importFile.getInputStream()) {
                extractedSystemFiles = extractSystemsFromZip(fs, exportDirectory);
            } catch (IOException e) {
                deleteFile(exportDirectory);
                throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                deleteFile(exportDirectory);
                throw e;
            }

            Set<String> servicesToImport = importInstructionsService.performServiceIgnoreInstructions(
                            extractedSystemFiles.stream()
                                    .map(ExportImportUtils::extractSystemIdFromFileName)
                                    .collect(Collectors.toSet()),
                            false)
                    .idsToImport();
            for (File singleSystemFile : extractedSystemFiles) {
                String serviceId = extractSystemIdFromFileName(singleSystemFile);
                if (!servicesToImport.contains(serviceId)) {
                    response.add(ImportSystemResult.builder()
                            .id(serviceId)
                            .name(serviceId)
                            .status(ImportSystemStatus.IGNORED)
                            .build());
                    log.info("Service {} ignored as a part of import exclusion list", serviceId);
                    continue;
                }

                ImportSystemResult result = importOneSystemInTransaction(singleSystemFile, systemIds);
                if (result != null) {
                    response.add(result);
                }
            }

            deleteFile(exportDirectory);
        } else {
            throw new RuntimeException("Unsupported file extension: " + fileExtension);
        }

        return response;
    }

    @Transactional(propagation = NOT_SUPPORTED)
    public ImportSystemsAndInstructionsResult importSystems(
            File importDirectory,
            SystemsCommitRequest systemCommitRequest,
            String importId,
            Set<String> technicalLabels
    ) {
        if (systemCommitRequest.getImportMode() == ImportMode.NONE) {
            return new ImportSystemsAndInstructionsResult();
        }

        List<File> systemsFiles;
        try {
            systemsFiles = extractSystemsFromImportDirectory(importDirectory.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
        }

        List<String> systemIds = systemCommitRequest.getImportMode() == ImportMode.FULL
                ? Collections.emptyList()
                : systemCommitRequest.getSystemIds();

        IgnoreResult ignoreResult = importInstructionsService.performServiceIgnoreInstructions(
                systemsFiles.stream()
                        .map(ExportImportUtils::extractSystemIdFromFileName)
                        .collect(Collectors.toSet()),
                true
        );
        int total = systemsFiles.size();
        int counter = 0;
        List<ImportSystemResult> response = new ArrayList<>();
        for (File systemFile : systemsFiles) {
            String serviceId = extractSystemIdFromFileName(systemFile);
            if (!ignoreResult.idsToImport().contains(serviceId)) {
                response.add(ImportSystemResult.builder()
                        .id(serviceId)
                        .name(serviceId)
                        .status(ImportSystemStatus.IGNORED)
                        .build());
                log.info("Service {} ignored as a part of import exclusion list", serviceId);
                continue;
            }

            importProgressService.calculateImportStatus(
                    importId, total, counter, ImportSessionService.COMMON_VARIABLES_IMPORT_PERCENTAGE_THRESHOLD, ImportSessionService.SERVICE_IMPORT_PERCENTAGE_THRESHOLD);
            counter++;

            ImportSystemResult result = importOneSystemInTransaction(systemFile, systemIds);

            if (result != null) {
                response.add(result);
            }
        }

        return new ImportSystemsAndInstructionsResult(response, ignoreResult.importInstructionResults());
    }

    protected synchronized ImportSystemResult importOneSystemInTransaction(File mainServiceFile, List<String> systemIds) {
        ImportSystemResult result;
        Optional<ContextSystem> baseSystemOptional = Optional.empty();

        try {
            ObjectNode serviceNode = getFileNode(mainServiceFile);
            SystemDeserializationResult deserializationResult = getBaseSystemDeserializationResult(serviceNode);
            baseSystemOptional = Optional.ofNullable(deserializationResult.getContextSystem());

            result = transactionTemplate.execute((status) -> {
                ContextSystem baseSystem = deserializationResult.getContextSystem();

                if (!CollectionUtils.isEmpty(systemIds) && !systemIds.contains(baseSystem.getId())) {
                    return null;
                }

                deserializationResult.setContextSystem(contextServiceDeserializer.deserializeSystem(
                        serviceNode));


                StringBuilder message = new StringBuilder();
                ImportSystemStatus importStatus = enrichAndSaveContextSystem(deserializationResult);

                return ImportSystemResult.builder()
                        .id(deserializationResult.getSystem().getId())
                        .name(deserializationResult.getSystem().getName())
                        .status(importStatus)
                        .message(message.toString())
                        .build();
            });
        } catch (Exception e) {
            result = ImportSystemResult.builder()
                    .id(baseSystemOptional.map(ContextSystem::getId).orElse(null))
                    .name(baseSystemOptional.map(ContextSystem::getName).orElse(""))
                    .status(ImportSystemStatus.ERROR)
                    .message(e.getMessage())
                    .build();
            log.warn("Exception when importing context system {} ({})", result.getName(), result.getId(), e);
        }

        return result;
    }

    private ImportSystemStatus enrichAndSaveContextSystem(SystemDeserializationResult deserializationResult) {
        ContextSystem system = deserializationResult.getContextSystem();
        ImportSystemStatus status;
        ContextSystem oldSystem = contextBaseService.getByIdOrNull(system.getId());
        if (oldSystem != null) {
            status = ImportSystemStatus.UPDATED;
        } else {
            status = ImportSystemStatus.CREATED;
        }
        StringBuilder compilationErrors = new StringBuilder();

        if (oldSystem != null) {
            contextBaseService.update(system);
        } else {
            contextBaseService.create(system, true);
        }

        return status;
    }


    protected SystemDeserializationResult getBaseSystemDeserializationResult(JsonNode serviceNode) throws JsonProcessingException {
        SystemDeserializationResult result = new SystemDeserializationResult();

        String systemId = serviceNode.get(AbstractSystemEntity.Fields.id) != null ? serviceNode.get(AbstractSystemEntity.Fields.id).asText(null) : null;
        if (systemId == null) {
            throw new RuntimeException("Missing id field in system file");
        }

        String systemName = serviceNode.get(AbstractSystemEntity.Fields.name) != null ? serviceNode.get(AbstractSystemEntity.Fields.name).asText("") : "";

        Timestamp modifiedWhen = serviceNode.get(AbstractSystemEntity.Fields.modifiedWhen) != null
                ? new Timestamp(serviceNode.get(AbstractSystemEntity.Fields.modifiedWhen).asLong()) : null;

        IntegrationSystem baseSystem = new IntegrationSystem();
        baseSystem.setId(systemId);
        baseSystem.setName(systemName);
        baseSystem.setModifiedWhen(modifiedWhen);

        result.setSystem(baseSystem);

        return result;
    }

    protected ObjectNode getFileNode(File file) throws IOException {
        return (ObjectNode) yamlMapper.readTree(file);
    }

    public void logSystemExportImport(ContextSystem system, String archiveName, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(system != null ? EntityType.CONTEXT_SYSTEM : EntityType.SERVICES)
                .entityId(system != null ? system.getId() : null)
                .entityName(system != null ? system.getName() : archiveName)
                .operation(operation)
                .build());
    }
}
