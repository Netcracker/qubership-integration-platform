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
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.SystemDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote.SystemCompareAction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.ServicesNotFoundException;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportMode;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.system.SystemsCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.auditing.AuditingHandler;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.ZIP_EXTENSION;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils.*;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;

@Service
@Slf4j
@Transactional
public class SystemExportImportService {
    private static final String LIB_COMPILATION_ERROR = "Failed to compile libraries for service specifications: ";
    private static final String SYSTEM_SAVED_DDL_ERROR = "System has been saved, but with DDL script execution error. ";
    private static final String CHAINS_REDEPLOY_NEEDED_MSG = "There are changes in environment address. Please redeploy affected chains (if any)";
    private static final String SPECIFICATION_EXISTS_ERROR_MESSAGE_START = "Specification with the version '";
    private static final String SPECIFICATION_EXISTS_BY_ID_ERROR_MESSAGE_START = "Specification with id '";
    private static final String SPECIFICATION_EXISTS_ERROR_MESSAGE_END = "' was not imported. ";
    protected static final String CONFIG_DEPLOY_LABELS = "deployLabels";

    private final TransactionTemplate transactionTemplate;
    private final YAMLMapper yamlMapper;
    private final SystemService systemService;
    private final SystemModelService systemModelService;
    private final EnvironmentService environmentService;
    protected final ActionsLogService actionLogger;
    private final AuditingHandler auditingHandler;
    private final ServiceSerializer serviceSerializer;
    private final ServiceDeserializer serviceDeserializer;
    private final ImportSessionService importProgressService;
    private final ImportInstructionsService importInstructionsService;

    @Value("${qip.export.remove-unused-specifications}")
    private boolean removeUnusedSpecs;

    @Autowired
    public SystemExportImportService(
            TransactionTemplate transactionTemplate,
            SystemService systemService,
            EnvironmentService environmentService,
            SystemModelService systemModelService,
            YAMLMapper yamlExportImportMapper,
            ActionsLogService actionLogger,
            AuditingHandler jpaAuditingHandler,
            ServiceSerializer serviceSerializer,
            ServiceDeserializer serviceDeserializer,
            ImportSessionService importProgressService,
            ImportInstructionsService importInstructionsService
    ) {
        this.transactionTemplate = transactionTemplate;
        this.yamlMapper = yamlExportImportMapper;
        this.systemService = systemService;
        this.environmentService = environmentService;
        this.systemModelService = systemModelService;
        this.actionLogger = actionLogger;
        this.auditingHandler = jpaAuditingHandler;
        this.serviceSerializer = serviceSerializer;
        this.serviceDeserializer = serviceDeserializer;
        this.importProgressService = importProgressService;
        this.importInstructionsService = importInstructionsService;
    }

    private void removeUnusedSpecifications(IntegrationSystem integrationSystem, List<String> usedSystemModelIds) {
        List<SpecificationGroup> specificationGroupToRemove = new ArrayList<>();

        for (SpecificationGroup specificationGroup : integrationSystem.getSpecificationGroups()) {
            List<SystemModel> systemModelsToRemove = new ArrayList<>();

            for (SystemModel systemModel : specificationGroup.getSystemModels()) {
                if (!usedSystemModelIds.contains(systemModel.getId())) {
                    systemModelsToRemove.add(systemModel);
                }
            }

            specificationGroup.getSystemModels().removeAll(systemModelsToRemove);

            if (specificationGroup.getSystemModels().isEmpty()) {
                specificationGroupToRemove.add(specificationGroup);
            }
        }

        integrationSystem.getSpecificationGroups().removeAll(specificationGroupToRemove);
    }

    private ExportedSystemObject exportOneSystem(IntegrationSystem system, List<String> usedSystemModelIds) {
        try {
            ExportedSystemObject exportedSystem;
            if (system != null) {
                if (removeUnusedSpecs && !CollectionUtils.isEmpty(usedSystemModelIds)) {
                    removeUnusedSpecifications(system, usedSystemModelIds);
                }
                exportedSystem = serviceSerializer.serialize(system);
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

    private List<ExportedSystemObject> exportSystems(List<IntegrationSystem> systems, List<String> usedSystemModelIds) {
        return systems.stream().map(system -> exportOneSystem(system, usedSystemModelIds)).collect(Collectors.toList());
    }

    public byte[] exportSystemsRequest(List<String> systemIds, List<String> usedSystemModelIds) {
        List<IntegrationSystem> systems = new ArrayList<>();
        if (systemIds == null) {
            systems.addAll(systemService.getAll());
        } else {
            systems.addAll(systemIds.stream().map(systemService::getByIdOrNull).filter(Objects::nonNull)
                    .toList());
        }
        if (systems.isEmpty()) {
            return null;
        }

        List<ExportedSystemObject> exportedSystems = exportSystems(systems, usedSystemModelIds);
        byte[] archive = serviceSerializer.writeSerializedArchive(exportedSystems);
        for (IntegrationSystem system : systems) {
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

    public List<ImportSystemResult> getSystemsImportPreview(File importDirectory, ImportInstructionsConfig instructionsConfig) {
        List<File> systemsFiles;
        try {
            systemsFiles = extractSystemsFromImportDirectory(importDirectory.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Error while extracting systems", e);
        }

        List<ImportSystemResult> importSystemResults = new ArrayList<>();
        for (File systemFile : systemsFiles) {
            importSystemResults.add(getSystemChanges(systemFile, instructionsConfig));
        }

        return importSystemResults;
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

            setCompareSystemResult(baseSystem, resultSystemCompareDTO);
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

    private void setCompareSystemResult(IntegrationSystem system, ImportSystemResult resultSystemCompareDTO) {
        IntegrationSystem oldSystem = systemService.getByIdOrNull(system.getId());;
        if (oldSystem == null) {
            resultSystemCompareDTO.setName(system.getName());
            resultSystemCompareDTO.setRequiredAction(SystemCompareAction.CREATE);
        } else {
            resultSystemCompareDTO.setName(oldSystem.getName());
            resultSystemCompareDTO.setRequiredAction(SystemCompareAction.UPDATE);
        }
    }

    @Transactional(propagation = NOT_SUPPORTED)
    public List<ImportSystemResult> importSystemRequest(MultipartFile importFile, List<String> systemIds, String deployLabel, Set<String> technicalLabels) {
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

                ImportSystemResult result = importOneSystemInTransaction(singleSystemFile, deployLabel, systemIds, technicalLabels);
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

        String deployLabel = systemCommitRequest.getDeployLabel();
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

            ImportSystemResult result = importOneSystemInTransaction(systemFile, deployLabel, systemIds, technicalLabels);

            if (result != null) {
                response.add(result);
            }
        }

        return new ImportSystemsAndInstructionsResult(response, ignoreResult.importInstructionResults());
    }

    protected synchronized ImportSystemResult importOneSystemInTransaction(File mainServiceFile, String deployLabel, List<String> systemIds, Set<String> technicalLabels) {
        ImportSystemResult result;
        Optional<IntegrationSystem> baseSystemOptional = Optional.empty();

        try {
            ObjectNode serviceNode = getFileNode(mainServiceFile);
            SystemDeserializationResult deserializationResult = getBaseSystemDeserializationResult(serviceNode);
            baseSystemOptional = Optional.ofNullable(deserializationResult.getSystem());

            result = transactionTemplate.execute((status) -> {
                File serviceDirectory = mainServiceFile.getParentFile();
                IntegrationSystem baseSystem = deserializationResult.getSystem();

                if (!CollectionUtils.isEmpty(systemIds) && !systemIds.contains(baseSystem.getId())) {
                    return null;
                }

                deserializationResult.setSystem(serviceDeserializer.deserializeSystem(
                        serviceNode, serviceDirectory));


                StringBuilder message = new StringBuilder();
                ImportSystemStatus importStatus = enrichAndSaveIntegrationSystem(deserializationResult, deployLabel, technicalLabels, message::append);

                return ImportSystemResult.builder()
                        .id(deserializationResult.getSystem().getId())
                        .name(deserializationResult.getSystem().getName())
                        .status(importStatus)
                        .message(message.toString())
                        .build();
            });
        } catch (Exception e) {
            result = ImportSystemResult.builder()
                    .id(baseSystemOptional.map(IntegrationSystem::getId).orElse(null))
                    .name(baseSystemOptional.map(IntegrationSystem::getName).orElse(""))
                    .status(ImportSystemStatus.ERROR)
                    .message(e.getMessage())
                    .build();
            log.warn("Exception when importing system {} ({})", result.getName(), result.getId(), e);
        }

        return result;
    }

    private ImportSystemStatus enrichAndSaveIntegrationSystem(SystemDeserializationResult deserializationResult, String deployLabel, Set<String> technicalLabels, Consumer<String> messageHandler) {
        IntegrationSystem system = deserializationResult.getSystem();
        ImportSystemStatus status;

        checkSpecificationUniqueness(system);

        IntegrationSystem oldSystem = systemService.getByIdOrNull(system.getId());
        replaceSystemTechnicalLabels(system, oldSystem, technicalLabels);

        Collection<SystemModel> newSystemModels = new ArrayList<>();
        if (oldSystem != null) {
            status = ImportSystemStatus.UPDATED;
            newSystemModels.addAll(prepareIntegrationSystemForUpdate(system, oldSystem, deployLabel, messageHandler, technicalLabels));
        } else {
            status = ImportSystemStatus.CREATED;
            prepareIntegrationSystemForCreate(system, deployLabel, messageHandler);
            system.getSpecificationGroups().stream()
                    .map(SpecificationGroup::getSystemModels)
                    .flatMap(Collection::stream)
                    .forEach(newSystemModels::add);
        }

        touchSystemFields(system);

        StringBuilder compilationErrors = new StringBuilder();
        boolean hasErrors = compileSystemModelLibraries(
                newSystemModels,
                (str) -> compilationErrors.append(str).append(" "));
        if (hasErrors) {
            throw new RuntimeException(LIB_COMPILATION_ERROR + compilationErrors);
        }

        if (oldSystem != null) {
            systemService.update(system);
        } else {
            systemService.create(system, true);
        }

        return status;
    }

    private boolean compileSystemModelLibraries(Collection<SystemModel> models, Consumer<String> errorHandler) {
        return models.stream()
                .map(model -> {
                    try {
                        systemModelService.patchModelWithCompiledLibrary(model);
                        return false;
                    } catch (Exception exception) {
                        errorHandler.accept(exception.getMessage());
                        return true;
                    }
                })
                .reduce(false, (r1, r2) -> r1 || r2);
    }

    private void touchSystemFields(IntegrationSystem system) {
        system.getEnvironments().forEach(auditingHandler::markModified);
        system.getSpecificationGroups().forEach(specificationGroup -> {
            auditingHandler.markModified(specificationGroup);
            specificationGroup.getSystemModels().forEach(systemModel -> {
                auditingHandler.markModified(systemModel);
                systemModel.getOperations().forEach(auditingHandler::markModified);
            });
        });
    }

    private Collection<SystemModel> prepareIntegrationSystemForUpdate(
            IntegrationSystem newSystem,
            IntegrationSystem oldSystem,
            String deployLabel,
            Consumer<String> messageHandler,
            Set<String> technicalLabels) {
        if (IntegrationSystemType.INTERNAL == newSystem.getIntegrationSystemType()) {
            if (newSystem.getEnvironments().size() > 1) {
                throw new RuntimeException("Can't have more than 1 environment on internal system");
            }

            Environment environment = newSystem.getEnvironments().isEmpty() ? null : newSystem.getEnvironments().get(0);
            Environment oldEnvironment = oldSystem.getEnvironments().isEmpty() ? null : oldSystem.getEnvironments().get(0);

            if (isInternalEnvironmentAddressChanged(environment, oldEnvironment)) {
                messageHandler.accept(CHAINS_REDEPLOY_NEEDED_MSG);
            }

            if (environment == null && oldEnvironment != null) {
                environmentService.deleteEnvironment(newSystem.getId(), oldEnvironment.getId());
            } else if (environment != null && oldEnvironment != null) {
                environment.setId(oldEnvironment.getId());
            }

            changeDiscoveredSourceLabels(newSystem, false);

        } else if (IntegrationSystemType.EXTERNAL == newSystem.getIntegrationSystemType()) {
            removeDuplicateLabels(newSystem, oldSystem);
            mergeEnvironmentsById(newSystem, oldSystem);

            setActiveEnvironmentId(newSystem, oldSystem, deployLabel, messageHandler);
        }
        mergeNonTechnicalServiceLabels(newSystem, oldSystem);
        return mergeSpecificationGroups(newSystem, oldSystem, messageHandler, technicalLabels);
    }

    private void mergeNonTechnicalServiceLabels(IntegrationSystem newSystem, IntegrationSystem oldSystem) {
        if (CollectionUtils.isEmpty(oldSystem.getLabels())) {
            return;
        }
        if (CollectionUtils.isEmpty(newSystem.getLabels())) {
            newSystem.setLabels(oldSystem.getLabels());
            return;
        }
        Set<String> existingLabelNames = oldSystem.getLabels().stream().filter(l -> !l.isTechnical()).map(l -> l.getName()).collect(Collectors.toSet());
        newSystem.getLabels().removeIf(l -> !l.isTechnical() && existingLabelNames.contains(l.getName()));
        newSystem.addLabels(oldSystem.getLabels().stream().filter(l -> !l.isTechnical()).collect(Collectors.toSet()));
    }

    private void replaceSystemTechnicalLabels(IntegrationSystem newSystem, IntegrationSystem oldSystem, Set<String> technicalLabels) {
        if (!CollectionUtils.isEmpty(technicalLabels)) {
            // Add technical labels from current system that are match name
            if (oldSystem != null) {
                newSystem.getLabels().addAll(oldSystem.getLabels().stream().filter(l -> l.isTechnical() && technicalLabels.contains(l.getName())).toList());
            }

            Set<String> technicalLabelsToAdd = technicalLabels.stream().filter(labelName -> newSystem.getLabels().stream().filter(AbstractLabel::isTechnical).noneMatch(l -> l.getName().equals(labelName))).collect(Collectors.toSet());
            technicalLabelsToAdd.forEach(labelName -> newSystem.addLabel(new IntegrationSystemLabel(labelName, newSystem, true)));
        }
        newSystem.getSpecificationGroups().forEach(group -> replaceSpecificationGroupTechnicalLabels(group, technicalLabels));
    }

    private void replaceSpecificationGroupTechnicalLabels(SpecificationGroup specificationGroup, Set<String> technicalLabels) {
        if (!CollectionUtils.isEmpty(technicalLabels)) {
            // Remove absent labels from db
            specificationGroup.getLabels().removeIf(label -> label.isTechnical() && !technicalLabels.contains(label.getName()));
            // Add to database only missing labels
            Set<String> currentSystemTechnicalLabels = specificationGroup.getLabels().stream().filter(AbstractLabel::isTechnical).map(AbstractLabel::getName).collect(Collectors.toSet());
            Set<String> technicalLabelsToAdd = technicalLabels.stream().filter(labelName -> !currentSystemTechnicalLabels.contains(labelName)).collect(Collectors.toSet());

            technicalLabelsToAdd.forEach(labelName -> specificationGroup.addLabel(new SpecificationGroupLabel(labelName, specificationGroup, true)));
        } else {
            specificationGroup.getLabels().removeIf(SpecificationGroupLabel::isTechnical);
        }
        specificationGroup.getSystemModels().forEach(systemModel -> replaceSpecificationTechnicalLabels(systemModel, technicalLabels));
    }

    private void replaceSpecificationTechnicalLabels(SystemModel systemModel, Set<String> technicalLabels) {
        if (!CollectionUtils.isEmpty(technicalLabels)) {
            // Remove absent labels from db
            systemModel.getLabels().removeIf(label -> label.isTechnical() && !technicalLabels.contains(label.getName()));
            // Add to database only missing labels
            Set<String> currentSystemTechnicalLabels = systemModel.getLabels().stream().filter(AbstractLabel::isTechnical).map(AbstractLabel::getName).collect(Collectors.toSet());
            Set<String> technicalLabelsToAdd = technicalLabels.stream().filter(labelName -> !currentSystemTechnicalLabels.contains(labelName)).collect(Collectors.toSet());

            technicalLabelsToAdd.forEach(labelName -> systemModel.addLabel(new SystemModelLabel(labelName, systemModel, true)));
        } else {
            systemModel.getLabels().removeIf(SystemModelLabel::isTechnical);
        }
    }

    private Collection<SystemModel> mergeSpecificationGroups(
            IntegrationSystem newSystem,
            IntegrationSystem oldSystem,
            Consumer<String> messageHandler,
            Set<String> technicalLabels) {
        Collection<SystemModel> addedNewModels = new ArrayList<>();

        Map<String, SystemModel> oldModelsIdFlatMap = new HashMap<>();
        Map<String, SpecificationGroup> oldSpecGroupsIdMap = new HashMap<>(oldSystem.getSpecificationGroups().size());
        Map<String, SpecificationGroup> oldSpecGroupsNameMap = new HashMap<>(oldSystem.getSpecificationGroups().size());
        for (SpecificationGroup oldGroup : oldSystem.getSpecificationGroups()) {
            oldModelsIdFlatMap.putAll(oldGroup.getSystemModels().stream().collect(Collectors.toMap(SystemModel::getId, Function.identity())));
            oldSpecGroupsIdMap.put(oldGroup.getId(), oldGroup);
            oldSpecGroupsNameMap.put(oldGroup.getName(), oldGroup);
        }

        for (Iterator<SpecificationGroup> newSpecGroupIterator = newSystem.getSpecificationGroups().iterator(); newSpecGroupIterator.hasNext(); ) {
            SpecificationGroup newSpecGroup = newSpecGroupIterator.next();
            SpecificationGroup sameOldSpecGroup = null;
            // <name, spec>
            Map<String, SystemModel> oldSpecGroupModelsNameMap = null;

            // check spec group conflicts
            if (oldSpecGroupsNameMap.containsKey(newSpecGroup.getName())) {
                if (oldSpecGroupsIdMap.containsKey(newSpecGroup.getId())) {
                    sameOldSpecGroup = oldSpecGroupsIdMap.get(newSpecGroup.getId());
                    try {
                        oldSpecGroupModelsNameMap = sameOldSpecGroup.getSystemModels().stream().collect(Collectors.toMap(
                                SystemModel::getName, Function.identity()));
                    } catch (IllegalStateException exception) {
                        throw new DuplicateKeyException("Specification with not unique name found in specification group " + sameOldSpecGroup.getName(), exception);
                    }
                } else {
                    addSuffixToSpecificationGroupName(newSpecGroup, oldSystem, newSystem);
                }
            }

            // spec id must be unique in a system
            newSpecGroup.getSystemModels().removeIf(spec -> {
                if (oldModelsIdFlatMap.containsKey(spec.getId())) {
                    messageHandler.accept(SPECIFICATION_EXISTS_BY_ID_ERROR_MESSAGE_START + spec.getId()
                            + SPECIFICATION_EXISTS_ERROR_MESSAGE_END + "It already exists in this service. ");
                    addUniqueSpecificationLabels(oldModelsIdFlatMap.get(spec.getId()), spec.getLabels());
                    return true;
                }
                return false;
            });

            // if group already exists
            if (sameOldSpecGroup != null) {
                for (SystemModel newSpecGroupModel : newSpecGroup.getSystemModels()) {
                    // spec name must be unique in a group
                    if (oldSpecGroupModelsNameMap.containsKey(newSpecGroupModel.getName())) {
                        addUniqueSpecificationLabels(oldSpecGroupModelsNameMap.get(newSpecGroupModel.getName()), newSpecGroupModel.getLabels());
                        // compare sources for a warning message
                        String warnMessage = newSpecGroupModel.isSourcesEquals(
                                oldSpecGroupModelsNameMap.get(newSpecGroupModel.getName()).getSpecificationSources(), false)
                                        ? ("It already exists in specification group '" + sameOldSpecGroup.getName() + "'. ")
                                        : ("Specification with same version and different file(s) content already exists. ");
                        messageHandler.accept(SPECIFICATION_EXISTS_ERROR_MESSAGE_START + newSpecGroupModel.getName()
                                + "' from group '" + newSpecGroupModel.getSpecificationGroup().getName()
                                + SPECIFICATION_EXISTS_ERROR_MESSAGE_END + warnMessage);
                    } else {
                        // spec has a unique name in a group
                        sameOldSpecGroup.addSystemModel(newSpecGroupModel);
                        addedNewModels.add(newSpecGroupModel);
                    }
                }
                replaceSpecificationGroupTechnicalLabels(sameOldSpecGroup, technicalLabels);
                addUniqueSpecificationGroupLabels(sameOldSpecGroup, newSpecGroup.getLabels());
                newSpecGroupIterator.remove();

            } else {
                addedNewModels.addAll(newSpecGroup.getSystemModels());
            }
        }
        return addedNewModels;
    }

    private void addUniqueSpecificationLabels(SystemModel specification, Set<SystemModelLabel> newLabels) {
        if (CollectionUtils.isEmpty(newLabels)) {
            return;
        }
        if (CollectionUtils.isEmpty(specification.getLabels())) {
            specification.setLabels(new HashSet<>());
        }
        Set<String> existingLabelNames = specification.getLabels().stream().filter(l -> !l.isTechnical()).map(l -> l.getName()).collect(Collectors.toSet());
        newLabels = new HashSet<>(newLabels);
        newLabels.removeIf(l -> l.isTechnical() || existingLabelNames.contains(l.getName()));
        specification.addLabels(newLabels);
    }

    private void addUniqueSpecificationGroupLabels(SpecificationGroup specGroup, Set<SpecificationGroupLabel> newLabels) {
        if (CollectionUtils.isEmpty(newLabels)) {
            return;
        }
        if (CollectionUtils.isEmpty(specGroup.getLabels())) {
            specGroup.setLabels(new HashSet<>());
        }
        Set<String> existingLabelNames = specGroup.getLabels().stream().filter(l -> !l.isTechnical()).map(l -> l.getName()).collect(Collectors.toSet());
        newLabels = new HashSet<>(newLabels);
        newLabels.removeIf(l -> l.isTechnical() || existingLabelNames.contains(l.getName()));
        specGroup.addLabels(newLabels);
    }

    private void addSuffixToSpecificationGroupName(
            SpecificationGroup group,
            IntegrationSystem oldSystem,
            IntegrationSystem newSystem
    ) {
        int counter = 0;
        String name = null;
        Set<String> existingNames = Stream.of(oldSystem, newSystem)
                .map(IntegrationSystem::getSpecificationGroups)
                .flatMap(Collection::stream)
                .map(SpecificationGroup::getName)
                .collect(Collectors.toSet());
        while (isNull(name) || existingNames.contains(name)) {
            counter++;
            name = String.format("%s (%d)", group.getName(), counter);
        }
        group.setName(name);
    }

    private void mergeEnvironmentsById(IntegrationSystem system, IntegrationSystem oldSystem) {
        List<Environment> environments = system.getEnvironments();
        for (Environment oldEnv : oldSystem.getEnvironments()) {
            if (environments.stream().noneMatch(newEnv -> newEnv.getId().equals(oldEnv.getId()))) {
                environments.add(oldEnv);
            }
        }
    }

    private void changeDiscoveredSourceLabels(IntegrationSystem system, boolean isNewSystem) {
        for (SpecificationGroup specificationGroup : system.getSpecificationGroups()) {
            for (SystemModel systemModel : specificationGroup.getSystemModels()) {
                SystemModelSource modelSourceType = systemModel.getSource();
                if (modelSourceType == null
                        || (SystemModelSource.DISCOVERED.equals(modelSourceType)
                                && isNotDiscoveredOnThisEnvironment(systemModel, isNewSystem))) {
                    systemModel.setSource(SystemModelSource.MANUAL);
                }
            }
        }
    }

    private boolean isNotDiscoveredOnThisEnvironment(SystemModel systemModel, boolean isNewSystem) {
        return isNewSystem || systemModelService.getSystemModelOrElseNull(systemModel.getId()) == null;
    }

    private boolean isInternalEnvironmentAddressChanged(Environment environment, Environment oldEnvironment) {
        String environmentAddress = environment == null ? "" : StringUtils.defaultString(environment.getAddress());
        String oldEnvironmentAddress = oldEnvironment == null ? "" : StringUtils.defaultString(oldEnvironment.getAddress());
        return !environmentAddress.equals(oldEnvironmentAddress);
    }

    private void removeDuplicateLabels(IntegrationSystem system, IntegrationSystem oldSystem) {
        List<Environment> updatedEnvironments = system.getEnvironments();
        for (Environment oldEnvironment : oldSystem.getEnvironments()) {
            if (updatedEnvironments.stream().noneMatch(e -> e.getId().equals(oldEnvironment.getId()))) {
                oldEnvironment.getLabels()
                        .removeIf(label -> updatedEnvironments.stream().anyMatch(e -> e.getLabels().contains(label)));
            }
        }
    }

    private void checkSpecificationUniqueness(IntegrationSystem system) {
        // check specification groups
        if (isNotUniqueByName(system.getSpecificationGroups())) {
            throw new DuplicateKeyException("Specification group with not unique name found");
        }

        // check specifications
        for (SpecificationGroup specificationGroup : system.getSpecificationGroups()) {
            if (isNotUniqueByName(specificationGroup.getSystemModels())) {
                throw new DuplicateKeyException("Specification with not unique version found in specification group " + specificationGroup.getName());
            }
        }
    }

    private boolean isNotUniqueByName(List<? extends AbstractSystemEntity> entities) {
        if (CollectionUtils.isEmpty(entities)) {
            return false;
        }
        return entities.size() != entities.stream()
                .map(AbstractSystemEntity::getName)
                .collect(Collectors.toSet())
                .size();
    }

    private void prepareIntegrationSystemForCreate(IntegrationSystem system, String deployLabel, Consumer<String> messageHandler) {
        changeDiscoveredSourceLabels(system, true);
        setActiveEnvironmentId(system, deployLabel, messageHandler);
    }

    private void setActiveEnvironmentId(IntegrationSystem system, String deployLabel, Consumer<String> messageHandler) {
        setActiveEnvironmentId(system, null, deployLabel, messageHandler);
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    private void setActiveEnvironmentId(IntegrationSystem system, IntegrationSystem oldSystem, String deployLabelString, Consumer<String> messageHandler) {
        String environmentIdToActivate = null;
        try {
            if (!StringUtils.isBlank(deployLabelString)) {
                EnvironmentLabel labelToActivate = EnvironmentLabel.valueOf(deployLabelString.toUpperCase());
                environmentIdToActivate = system.getEnvironments().stream()
                        .filter(env -> env.getLabels().stream().anyMatch(l -> l.equals(labelToActivate)))
                        .map(Environment::getId).findAny().orElse(null);

            }
        } catch (IllegalArgumentException ignored) {
        }

        if (!StringUtils.isBlank(environmentIdToActivate)) {
            system.setActiveEnvironmentId(environmentIdToActivate);
        } else if (oldSystem != null && !StringUtils.isBlank(oldSystem.getActiveEnvironmentId())) {
            system.setActiveEnvironmentId(oldSystem.getActiveEnvironmentId());
        }
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

    public void logSystemExportImport(IntegrationSystem system, String archiveName, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(system != null ? EntityType.getSystemType(system) : EntityType.SERVICES)
                .entityId(system != null ? system.getId() : null)
                .entityName(system != null ? system.getName() : archiveName)
                .operation(operation)
                .build());
    }
}
