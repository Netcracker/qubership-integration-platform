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
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServicesNotFoundException;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.SystemDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote.SystemCompareAction;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportMode;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.system.SystemsCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.qubership.integration.platform.runtime.catalog.util.EnvironmentLimitUtils;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
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
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.ZIP_EXTENSION;
import static org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils.*;
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

    // Every name a plain service file can carry: the three per-type postfixes, plus the older `.service.`.
    // `ExportImportUtils` ORs the deprecated flat `service-` prefix in on its own, and recognizes this scan as the
    // plain-service one by these postfixes, so the list has to come from there. A context or MCP service matches none
    // of them, because `.context-service.` does not contain `.service.`, and is imported elsewhere.
    private static final Collection<String> SERVICE_FILE_POSTFIXES = ExportImportUtils.plainServicePostfixes();

    private final TransactionTemplate transactionTemplate;
    private final YAMLMapper yamlMapper;
    private final SystemService systemService;
    private final SystemModelService systemModelService;
    private final EnvironmentService environmentService;
    protected final ActionsLogService actionLogger;
    private final AuditingHandler auditingHandler;
    private final ServiceSerializer serviceSerializer;
    private final ServiceDeserializer serviceDeserializer;
    private final ArchiveWriter archiveWriter;
    private final ImportSessionService importProgressService;
    private final ImportInstructionsService importInstructionsService;
    private final ElementHelperService elementHelperService;
    private final ChainService chainService;
    private final ApiGroupService apiGroupService;
    private final ServiceTypeFiles serviceTypeFiles;

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
            ArchiveWriter archiveWriter,
            ImportSessionService importProgressService,
            ImportInstructionsService importInstructionsService,
            ElementHelperService elementHelperService,
            ChainService chainService,
            ApiGroupService apiGroupService,
            ServiceTypeFiles serviceTypeFiles
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
        this.archiveWriter = archiveWriter;
        this.importProgressService = importProgressService;
        this.importInstructionsService = importInstructionsService;
        this.elementHelperService = elementHelperService;
        this.chainService = chainService;
        this.apiGroupService = apiGroupService;
        this.serviceTypeFiles = serviceTypeFiles;
    }

    private void removeUnusedSpecifications(IntegrationSystem integrationSystem, List<String> usedSystemModelIds) {
        List<ApiGroup> specificationGroupToRemove = new ArrayList<>();

        for (ApiGroup specificationGroup : integrationSystem.getApiGroups()) {
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

        integrationSystem.getApiGroups().removeAll(specificationGroupToRemove);
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
        } catch (IllegalArgumentException e) {
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
        byte[] archive = archiveWriter.writeArchive(exportedSystems);
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
                extractedSystemFiles = extractSystemsFromZip(fs, exportDirectory, SERVICE_FILE_POSTFIXES);
            } catch (ServicesNotFoundException e) {
                deleteFile(exportDirectory);
            } catch (IOException e) {
                deleteFile(exportDirectory);
                throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                deleteFile(exportDirectory);
                throw e;
            }

            DiscoveredServiceFiles discovered = discovered(extractedSystemFiles);
            response.addAll(discovered.previewErrors());
            ImportInstructionsConfig instructionsConfig = importInstructionsService
                    .getServiceImportInstructionsConfig(Set.of(ImportInstructionAction.IGNORE));
            for (File singleSystemFile : discovered.importable()) {
                response.add(getSystemChanges(singleSystemFile, instructionsConfig));
            }
            deleteFile(exportDirectory);
        } else {
            throw new RuntimeException("Unsupported file extension: " + fileExtension);
        }

        return response;
    }

    /**
     * The discovered service files split into the ones a single file describes and the ids two or more files claim.
     *
     * <p>The split belongs over the whole discovered list, because the per-file loop below deserializes each file in
     * its own transaction: the two copies never meet, and the second one silently overwrites the first. A colliding id
     * degrades to an error row rather than refusing the archive, because {@code GeneralImportService} imports
     * instructions and common variables before the services, so a throw here ends a session already half applied.
     */
    private record DiscoveredServiceFiles(List<File> importable, Map<String, List<String>> collidingFileNamesById) {

        static DiscoveredServiceFiles of(List<File> serviceFiles) {
            // Sorted, so the reported rows do not follow the order the file walk happened to return.
            Map<String, List<File>> filesById = serviceFiles.stream().collect(Collectors.groupingBy(
                    ExportImportUtils::extractSystemIdFromFileName, TreeMap::new, Collectors.toList()));
            List<File> importable = new ArrayList<>();
            Map<String, List<String>> colliding = new TreeMap<>();
            filesById.forEach((id, files) -> {
                if (files.size() > 1) {
                    colliding.put(id, files.stream().map(File::getName).sorted().toList());
                } else {
                    importable.addAll(files);
                }
            });
            return new DiscoveredServiceFiles(importable, colliding);
        }

        List<ImportSystemResult> previewErrors() {
            return collidingFileNamesById.entrySet().stream()
                    .<ImportSystemResult>map(entry -> ImportSystemResult.builder()
                            .id(entry.getKey())
                            .name(entry.getKey())
                            .requiredAction(SystemCompareAction.ERROR)
                            .message(collisionMessage(entry.getValue()))
                            .build())
                    .toList();
        }

        /** Every service id the archive names, colliding ones included: an IGNORE instruction applies to those too. */
        Set<String> discoveredIds() {
            return Stream.concat(
                            importable.stream().map(ExportImportUtils::extractSystemIdFromFileName),
                            collidingFileNamesById.keySet().stream())
                    .collect(Collectors.toSet());
        }

        /**
         * The rows a colliding id produces on a commit path, under the selection and ignore rules the per-file loop
         * applies to every other id. Without them a service the request never selected, or one an IGNORE instruction
         * excludes, would turn into an error row over a file the import was never going to read, and a single error
         * row marks the whole session failed.
         */
        List<ImportSystemResult> commitErrors(Set<String> idsToImport, List<String> selectedIds) {
            List<ImportSystemResult> results = new ArrayList<>();
            collidingFileNamesById.forEach((serviceId, fileNames) -> {
                if (!CollectionUtils.isEmpty(selectedIds) && !selectedIds.contains(serviceId)) {
                    return;
                }
                if (!idsToImport.contains(serviceId)) {
                    results.add(ImportSystemResult.builder()
                            .id(serviceId)
                            .name(serviceId)
                            .status(ImportSystemStatus.IGNORED)
                            .build());
                    log.info("Service {} ignored as a part of import exclusion list", serviceId);
                    return;
                }
                results.add(ImportSystemResult.builder()
                        .id(serviceId)
                        .name(serviceId)
                        .status(ImportSystemStatus.ERROR)
                        .message(collisionMessage(fileNames))
                        .build());
            });
            return results;
        }

        static String collisionMessage(List<String> fileNames) {
            return String.format(
                    "Archive holds %d service files for this service: %s. Keep the one that is current and remove the"
                            + " others, then re-import. The service is not imported.",
                    fileNames.size(), String.join(", ", fileNames));
        }
    }

    /**
     * The discovered files this import may act on, with the ones another import already has left out.
     *
     * <p>The legacy flat name belongs to the plain-service scan whatever the id spells, so
     * {@code service-ctx.context-service.qip.yaml} reaches it as the flat file of {@code ctx.context-service.qip}
     * while the context import reads the same name as the context file of {@code service-ctx}. Both claims are kept:
     * dropping the plain one hides a legacy file whose id spells another kind's postfix. Only one of them may end in a
     * row, though — the document states a context service, the context import creates it, and a plain-service error
     * row on top of that fails a session that succeeded.
     *
     * <p>Filtering before the collision split, so a file another import has also cannot collide with anything here.
     */
    private DiscoveredServiceFiles discovered(List<File> serviceFiles) {
        return DiscoveredServiceFiles.of(serviceFiles.stream().filter(this::isPlainServiceFile).toList());
    }

    /** An unreadable file stays: the import reports what is wrong with it, which is more than a skip would say. */
    private boolean isPlainServiceFile(File serviceFile) {
        try {
            if (serviceTypeFiles.isContextOrMCPServiceFile(serviceFile.getName(), yamlMapper.readTree(serviceFile))) {
                log.info("File {} states a context or an MCP service, which is imported by its own service import",
                        serviceFile.getName());
                return false;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot read {} to tell which service kind it states: {}", serviceFile.getName(), e.getMessage());
        }
        return true;
    }

    public List<ImportSystemResult> getSystemsImportPreview(File importDirectory, ImportInstructionsConfig instructionsConfig) {
        List<File> systemsFiles;
        try {
            systemsFiles = extractSystemsFromImportDirectory(importDirectory.getAbsolutePath(), SERVICE_FILE_POSTFIXES);
        } catch (Exception e) {
            throw new RuntimeException("Error while extracting systems", e);
        }
        DiscoveredServiceFiles discovered = discovered(systemsFiles);

        List<ImportSystemResult> importSystemResults = new ArrayList<>(discovered.previewErrors());
        for (File systemFile : discovered.importable()) {
            importSystemResults.add(getSystemChanges(systemFile, instructionsConfig));
        }

        return importSystemResults;
    }

    protected ImportSystemResult getSystemChanges(File mainSystemFile, ImportInstructionsConfig instructionsConfig) {
        ImportSystemResult resultSystemCompareDTO;

        String systemId = null;
        String systemName = null;

        try {
            JsonNode serviceNode = yamlMapper.readTree(mainSystemFile);
            SystemDeserializationResult deserializationResult = getBaseSystemDeserializationResult(serviceNode);
            IntegrationSystem baseSystem = deserializationResult.getSystem();
            systemId = baseSystem.getId();
            systemName = baseSystem.getName();
            // The same rule the commit path runs, so a file stating no type, or a name and a field that disagree,
            // shows up as an error row here instead of only after the user commits.
            ServiceTypeFiles.typeFromDocument(serviceNode).ifPresent(baseSystem::setIntegrationSystemType);
            serviceDeserializer.resolveServiceType(baseSystem, mainSystemFile);
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
        IntegrationSystem oldSystem = systemService.getByIdOrNull(system.getId());
        if (oldSystem == null) {
            resultSystemCompareDTO.setName(system.getName());
            resultSystemCompareDTO.setRequiredAction(SystemCompareAction.CREATE);
        } else {
            // The third refusal rule of the commit path, run here for the same reason as the other two: the stored
            // service is already loaded, and a refusal the user only meets after committing is not a preview.
            validateServiceTypeUnchanged(system, oldSystem);
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
                extractedSystemFiles = extractSystemsFromZip(fs, exportDirectory, SERVICE_FILE_POSTFIXES);
            } catch (IOException e) {
                deleteFile(exportDirectory);
                throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                deleteFile(exportDirectory);
                throw e;
            }

            DiscoveredServiceFiles discovered = discovered(extractedSystemFiles);
            Set<String> servicesToImport = importInstructionsService
                    .performServiceIgnoreInstructions(discovered.discoveredIds(), false)
                    .idsToImport();
            response.addAll(discovered.commitErrors(servicesToImport, systemIds));
            for (File singleSystemFile : discovered.importable()) {
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

        List<File> discoveredFiles;
        try {
            discoveredFiles = extractSystemsFromImportDirectory(
                    importDirectory.getAbsolutePath(), SERVICE_FILE_POSTFIXES);
        } catch (IOException e) {
            throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage(), e);
        }
        DiscoveredServiceFiles discovered = discovered(discoveredFiles);
        List<File> systemsFiles = discovered.importable();

        String deployLabel = systemCommitRequest.getDeployLabel();
        List<String> systemIds = systemCommitRequest.getImportMode() == ImportMode.FULL
                ? Collections.emptyList()
                : systemCommitRequest.getSystemIds();

        IgnoreResult ignoreResult =
                importInstructionsService.performServiceIgnoreInstructions(discovered.discoveredIds(), true);
        int total = systemsFiles.size();
        int counter = 0;
        List<ImportSystemResult> response =
                new ArrayList<>(discovered.commitErrors(ignoreResult.idsToImport(), systemIds));
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
            JsonNode serviceNode = yamlMapper.readTree(mainServiceFile);
            SystemDeserializationResult deserializationResult = getBaseSystemDeserializationResult(serviceNode);
            baseSystemOptional = Optional.ofNullable(deserializationResult.getSystem());

            result = transactionTemplate.execute((status) -> {
                IntegrationSystem baseSystem = deserializationResult.getSystem();

                if (!CollectionUtils.isEmpty(systemIds) && !systemIds.contains(baseSystem.getId())) {
                    return null;
                }

                deserializationResult.setSystem(serviceDeserializer.deserializeSystem(mainServiceFile));

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
            system.getApiGroups().stream()
                    .map(ApiGroup::getSystemModels)
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
        system.getApiGroups().forEach(specificationGroup -> {
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
        validateServiceTypeUnchanged(newSystem, oldSystem);
        EnvironmentLimitUtils.validate(newSystem, newSystem.getEnvironments().size());

        if (IntegrationSystemType.INTERNAL == newSystem.getIntegrationSystemType()) {
            Environment environment = newSystem.getEnvironments().isEmpty() ? null : newSystem.getEnvironments().get(0);
            Environment oldEnvironment = oldSystem.getEnvironments().isEmpty() ? null : oldSystem.getEnvironments().get(0);

            if (isInternalEnvironmentAddressChanged(environment, oldEnvironment)) {
                messageHandler.accept(CHAINS_REDEPLOY_NEEDED_MSG);
            }

            if (environment == null && oldEnvironment != null) {
                environmentService.deleteEnvironment(newSystem.getId(), oldEnvironment.getId());
            } else if (environment != null && oldEnvironment != null) {
                checkEnvironmentEquality(environment, oldEnvironment);
                environment.setId(oldEnvironment.getId());
            } else if (environment != null && oldEnvironment == null) {
                markChainAsUnsaved(environment);
            }

            changeDiscoveredSourceLabels(newSystem, false);

        } else if (IntegrationSystemType.EXTERNAL == newSystem.getIntegrationSystemType()) {
            removeDuplicateLabels(newSystem, oldSystem);
            mergeEnvironmentsById(newSystem, oldSystem);

            setActiveEnvironmentId(newSystem, oldSystem, deployLabel, messageHandler);
            checkActiveEnvironmentEquality(newSystem, oldSystem);
        }
        mergeNonTechnicalServiceLabels(newSystem, oldSystem);
        return mergeSpecificationGroups(newSystem, oldSystem, messageHandler, technicalLabels);
    }

    /**
     * The type belongs to the stored service, not to the file that updates it. Switching it would re-interpret the
     * service's environment limit, allowed protocols, and action-log entity type against data written under the old
     * rules, so an import that disagrees with the stored type is refused instead of applied.
     *
     * <p>A stored null is a legacy row that never had a type, so an import that states one repairs it.
     */
    private static void validateServiceTypeUnchanged(IntegrationSystem newSystem, IntegrationSystem oldSystem) {
        IntegrationSystemType storedType = oldSystem.getIntegrationSystemType();
        IntegrationSystemType importedType = newSystem.getIntegrationSystemType();
        if (storedType == null || importedType == null || storedType == importedType) {
            return;
        }
        throw new ServiceImportException(newSystem.getId(), newSystem.getName(), String.format(
                "Service %s (id %s) is stored as %s and the imported file states %s, and a service type cannot be"
                        + " changed by an import. Import the file under a new id, or delete the service first."
                        + " The service is left as %s.",
                oldSystem.getName(), oldSystem.getId(), storedType, importedType, storedType));
    }

    private void mergeNonTechnicalServiceLabels(IntegrationSystem newSystem, IntegrationSystem oldSystem) {
        if (CollectionUtils.isEmpty(oldSystem.getLabels())) {
            return;
        }
        if (CollectionUtils.isEmpty(newSystem.getLabels())) {
            newSystem.setLabels(oldSystem.getLabels());
            return;
        }
        Set<String> existingLabelNames = oldSystem.getLabels().stream().filter(l -> !l.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet());
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
        newSystem.getApiGroups().forEach(group -> replaceSpecificationGroupTechnicalLabels(group, technicalLabels));
    }

    private void replaceSpecificationGroupTechnicalLabels(ApiGroup specificationGroup, Set<String> technicalLabels) {
        if (!CollectionUtils.isEmpty(technicalLabels)) {
            // Remove absent labels from db
            specificationGroup.getLabels().removeIf(label -> label.isTechnical() && !technicalLabels.contains(label.getName()));
            // Add to database only missing labels
            Set<String> currentSystemTechnicalLabels = specificationGroup.getLabels().stream().filter(AbstractLabel::isTechnical).map(AbstractLabel::getName).collect(Collectors.toSet());
            Set<String> technicalLabelsToAdd = technicalLabels.stream().filter(labelName -> !currentSystemTechnicalLabels.contains(labelName)).collect(Collectors.toSet());

            technicalLabelsToAdd.forEach(labelName -> specificationGroup.addLabel(new ApiGroupLabel(labelName, specificationGroup, true)));
        } else {
            specificationGroup.getLabels().removeIf(ApiGroupLabel::isTechnical);
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
        Map<String, ApiGroup> oldSpecGroupsIdMap = HashMap.newHashMap(oldSystem.getApiGroups().size());
        Map<String, ApiGroup> oldSpecGroupsNameMap = HashMap.newHashMap(oldSystem.getApiGroups().size());
        for (ApiGroup oldGroup : oldSystem.getApiGroups()) {
            oldModelsIdFlatMap.putAll(oldGroup.getSystemModels().stream().collect(Collectors.toMap(SystemModel::getId, Function.identity())));
            oldSpecGroupsIdMap.put(oldGroup.getId(), oldGroup);
            oldSpecGroupsNameMap.put(oldGroup.getName(), oldGroup);
        }

        for (Iterator<ApiGroup> newSpecGroupIterator = newSystem.getApiGroups().iterator(); newSpecGroupIterator.hasNext(); ) {
            ApiGroup newSpecGroup = newSpecGroupIterator.next();
            ApiGroup sameOldSpecGroup = null;
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
                                              + "' from group '" + newSpecGroupModel.getApiGroup().getName()
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
        Set<String> existingLabelNames = specification.getLabels().stream().filter(l -> !l.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet());
        newLabels = new HashSet<>(newLabels);
        newLabels.removeIf(l -> l.isTechnical() || existingLabelNames.contains(l.getName()));
        specification.addLabels(newLabels);
    }

    private void addUniqueSpecificationGroupLabels(ApiGroup specGroup, Set<ApiGroupLabel> newLabels) {
        if (CollectionUtils.isEmpty(newLabels)) {
            return;
        }
        if (CollectionUtils.isEmpty(specGroup.getLabels())) {
            specGroup.setLabels(new HashSet<>());
        }
        Set<String> existingLabelNames = specGroup.getLabels().stream().filter(l -> !l.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet());
        newLabels = new HashSet<>(newLabels);
        newLabels.removeIf(l -> l.isTechnical() || existingLabelNames.contains(l.getName()));
        specGroup.addLabels(newLabels);
    }

    private void addSuffixToSpecificationGroupName(
            ApiGroup group,
            IntegrationSystem oldSystem,
            IntegrationSystem newSystem
    ) {
        int counter = 0;
        String name = null;
        Set<String> existingNames = Stream.of(oldSystem, newSystem)
                .map(IntegrationSystem::getApiGroups)
                .flatMap(Collection::stream)
                .map(ApiGroup::getName)
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
        for (ApiGroup specificationGroup : system.getApiGroups()) {
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

    private void checkActiveEnvironmentEquality(IntegrationSystem system, IntegrationSystem oldSystem) {
        String currentActiveEnv = system.getActiveEnvironmentId();
        String oldActiveEnv = oldSystem.getActiveEnvironmentId();

        if (!Objects.equals(currentActiveEnv, oldActiveEnv)) {
            return;
        }

        Environment currentEnv = findEnvironment(system, currentActiveEnv);
        Environment oldEnv = findEnvironment(oldSystem, oldActiveEnv);

        if (currentEnv != null && oldEnv != null) {
            checkEnvironmentEquality(currentEnv, oldEnv);
        }
    }

    private Environment findEnvironment(IntegrationSystem system, String envId) {
        return system.getEnvironments().stream()
                .filter(e -> Objects.equals(e.getId(), envId))
                .findFirst()
                .orElse(null);
    }

    private void checkEnvironmentEquality(Environment newEnvironment, Environment oldEnvironment) {
        if (!oldEnvironment.equals(newEnvironment)) {
            markChainAsUnsaved(newEnvironment);
        }
    }

    private void markChainAsUnsaved(Environment environment) {
        List<Chain> chains = elementHelperService.findChainBySystemId(environment.getSystem().getId());
        chains.forEach(chainService::markChainAsUnsaved);
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
        if (isNotUniqueByName(system.getApiGroups())) {
            throw new DuplicateKeyException("Specification group with not unique name found");
        }

        // check specifications
        for (ApiGroup specificationGroup : system.getApiGroups()) {
            if (isNotUniqueByName(specificationGroup.getSystemModels())) {
                throw new DuplicateKeyException("Specification with not unique version found in specification group " + specificationGroup.getName());
            }
        }

        apiGroupService.checkSpecificationGroupUniqueness(system);
        systemModelService.checkSystemModelUniqueness(system);
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
        EnvironmentLimitUtils.validate(system, system.getEnvironments().size());
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

        String systemId = Optional.ofNullable(serviceNode.get("id")).map(JsonNode::asText)
                .orElseThrow(() -> new RuntimeException("Missing id field in system file"));
        String systemName = Optional.ofNullable(serviceNode.get("name")).map(JsonNode::asText).orElse("");

        Timestamp modifiedWhen = Optional.ofNullable(serviceNode.get("context"))
                .map(node -> node.get("modifiedWhen"))
                .or(() -> Optional.ofNullable(serviceNode.get("modifiedWhen")))
                .map(JsonNode::asLong)
                .map(Timestamp::new)
                .orElse(null);

        IntegrationSystem baseSystem = new IntegrationSystem();
        baseSystem.setId(systemId);
        baseSystem.setName(systemName);
        baseSystem.setModifiedWhen(modifiedWhen);

        result.setSystem(baseSystem);

        return result;
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
