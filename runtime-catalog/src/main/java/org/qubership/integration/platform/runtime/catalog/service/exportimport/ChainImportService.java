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

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.catalog.exception.ChainDifferenceClientException;
import org.qubership.integration.platform.catalog.exception.ChainDifferenceException;
import org.qubership.integration.platform.catalog.exception.ComparisonEntityNotFoundException;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ChainImportInstructionsConfig;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.*;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.difference.ChainDifferenceRequest;
import org.qubership.integration.platform.catalog.service.difference.ChainDifferenceService;
import org.qubership.integration.platform.catalog.service.difference.EntityDifferenceResult;
import org.qubership.integration.platform.catalog.util.ChainUtils;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.*;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ChainsIgnoreOverrideResult;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportChainPreviewDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.ChainImportException;
import org.qubership.integration.platform.runtime.catalog.service.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ChainDeployPrepare;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ChainFileMigrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.SYSTEM_ID;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;

@Slf4j
@Service
public class ChainImportService {

    private static final String CHAINS_HASH_MATCHED_LOG_MESSAGE = "Chain {} fully matched by hash with its existing copy on the instance, hence skipped.";

    private final YAMLMapper yamlMapper;
    private final TransactionTemplate transactionTemplate;
    private final ChainService chainService;
    private final FolderService folderService;
    private final SnapshotService snapshotService;
    private final DeploymentService deploymentService;
    private final EngineService engineService;
    private final ChainExternalEntityMapper chainExternalEntityMapper;
    private final ImportSessionService importProgressService;
    private final ActionsLogService actionsLogService;
    private final DependencyService dependencyService;
    private final ElementService elementService;
    private final MaskedFieldsService maskedFieldsService;
    private final ChainDifferenceService chainDifferenceService;
    private final ImportInstructionsService importInstructionsService;
    private final ChainFileMigrationService chainFileMigrationService;

    @Value("${qip.build.artifact-descriptor-version}")
    private String artifactDescriptorVersion;

    @Autowired
    public ChainImportService(
            YAMLMapper yamlMapper,
            TransactionTemplate transactionTemplate,
            ChainService chainService,
            FolderService folderService,
            SnapshotService snapshotService,
            DeploymentService deploymentService,
            EngineService engineService,
            ChainExternalEntityMapper chainExternalEntityMapper,
            ImportSessionService importProgressService,
            ActionsLogService actionsLogService,
            DependencyService dependencyService,
            ElementService elementService,
            MaskedFieldsService maskedFieldsService,
            ChainDifferenceService chainDifferenceService,
            ImportInstructionsService importInstructionsService,
            ChainFileMigrationService chainFileMigrationService
    ) {
        this.yamlMapper = yamlMapper;
        this.transactionTemplate = transactionTemplate;
        this.chainService = chainService;
        this.folderService = folderService;
        this.snapshotService = snapshotService;
        this.deploymentService = deploymentService;
        this.engineService = engineService;
        this.chainExternalEntityMapper = chainExternalEntityMapper;
        this.importProgressService = importProgressService;
        this.actionsLogService = actionsLogService;
        this.dependencyService = dependencyService;
        this.elementService = elementService;
        this.maskedFieldsService = maskedFieldsService;
        this.chainDifferenceService = chainDifferenceService;
        this.importInstructionsService = importInstructionsService;
        this.chainFileMigrationService = chainFileMigrationService;
    }

    public List<ImportChainPreviewDTO> getChainsImportPreview(File importDirectory, ChainImportInstructionsConfig instructionsConfig) {
        File[] chainDirs = new File(importDirectory + File.separator + CHAINS_ARCH_PARENT_DIR)
                .listFiles(File::isDirectory);

        if (ArrayUtils.isEmpty(chainDirs)) {
            return Collections.emptyList();
        }

        List<ImportChainPreviewDTO> importChainPreviewDTOS = new ArrayList<>();
        for (File chainDir : chainDirs) {
            importChainPreviewDTOS.add(restoreChainsFromDirForPreview(chainDir, instructionsConfig));
        }

        return importChainPreviewDTOS;
    }

    public EntityDifferenceResult compareChains(File importDirectory, ChainDifferenceRequest diffRequest) {
        File[] chainDirs = new File(importDirectory + File.separator + CHAINS_ARCH_PARENT_DIR)
                .listFiles(File::isDirectory);

        if (ArrayUtils.isEmpty(chainDirs)) {
            throw new ChainDifferenceClientException("Imported chain directory not found for compare");
        }
        File chainDir = Optional.ofNullable(chainDirs)
                .map(Stream::of)
                .flatMap(dirsStream -> dirsStream
                        .filter(dir -> StringUtils.equals(dir.getName(), diffRequest.getRightChainId()))
                        .findFirst())
                .orElseThrow(() -> new ChainDifferenceClientException(
                        "Imported chain with id " + diffRequest.getRightChainId() + " not found in the archive"));

        Chain rightChain;
        try {
            String chainYAML = Files.readString(getChainYAMLFile(chainDir).toPath());
            chainYAML = migrateToActualFileVersion(chainYAML);
            ChainExternalEntity chainExternalEntity = yamlMapper.readValue(chainYAML, ChainExternalEntity.class);
            rightChain = chainExternalEntityMapper.toInternalEntity(ChainExternalMapperEntity.builder()
                    .chainExternalEntity(chainExternalEntity)
                    .chainFilesDirectory(chainDir)
                    .build());
        } catch (Exception e) {
            throw new ChainDifferenceException("Exception while converting " + diffRequest.getRightChainId() + " chain for compare", e);
        }

        if (diffRequest.getLeftSnapshotId() == null) {
            return chainDifferenceService.findChainsDifferences(
                    chainService.tryFindById(diffRequest.getLeftChainId())
                            .orElseThrow(() -> new ComparisonEntityNotFoundException("Can't find chain with id: " + diffRequest.getLeftChainId())),
                    rightChain
            );
        }
        return chainDifferenceService.findChainsDifferences(
                snapshotService.tryFindById(diffRequest.getLeftSnapshotId())
                        .orElseThrow(() -> new ComparisonEntityNotFoundException("Can't find snapshot with id: " + diffRequest.getLeftSnapshotId())),
                rightChain
        );
    }

    public ImportChainsAndInstructionsResult importChains(
            File importDirectory,
            List<ChainCommitRequest> commitRequests,
            String importId,
            Set<String> technicalLabels,
            boolean validateByHash
    ) {
        ImportChainsAndInstructionsResult importResult = restoreChainsFromDir(
                importDirectory, new ArrayList<>(commitRequests), importId, technicalLabels, validateByHash
        );
        makeDeployActions(importResult.chainResults(), commitRequests, importId, technicalLabels);

        return importResult;
    }

    private ImportChainPreviewDTO restoreChainsFromDirForPreview(File chainDir, ChainImportInstructionsConfig importInstructionsConfig) {
        ImportChainPreviewDTO importChainPreview;

        try {
            File chainYAMLFile = getChainYAMLFile(chainDir);
            String chainYaml = migrateToActualFileVersion(Files.readString(chainYAMLFile.toPath()));
            ChainExternalEntity chainExternalEntity = yamlMapper.readValue(chainYaml, ChainExternalEntity.class);
            Set<String> usedSystemIds = new HashSet<>();
            collectUsedSystemIds(chainExternalEntity.getContent().getElements(), usedSystemIds);
            Boolean chainExists = chainService.exists(chainExternalEntity.getId());
            ImportInstructionAction instructionAction = null;
            if (importInstructionsConfig.getIgnore().contains(chainExternalEntity.getId())) {
                instructionAction = ImportInstructionAction.IGNORE;
            } else if (importInstructionsConfig.getOverride().stream().anyMatch(override -> chainExternalEntity.getId().equals(override.getId()))) {
                instructionAction = ImportInstructionAction.OVERRIDE;
            }

            importChainPreview = ImportChainPreviewDTO.builder()
                    .id(chainExternalEntity.getId())
                    .name(chainExternalEntity.getName())
                    .usedSystems(usedSystemIds)
                    .deployments(chainExternalEntity.getContent().getDeployments())
                    .deployAction(chainExternalEntity.getContent().getDeployAction())
                    .instructionAction(instructionAction)
                    .exists(chainExists)
                    .build();
        } catch (ChainImportException e) {
            log.warn("Error while chain import: ", e);
            importChainPreview = new ImportChainPreviewDTO();
            importChainPreview.setId(e.getChainId());
            importChainPreview.setName(e.getChainName());
            importChainPreview.setErrorMessage(e.getMessage());
        } catch (Exception e) {
            log.warn("Exception while chain import: ", e);
            importChainPreview = new ImportChainPreviewDTO();
            importChainPreview.setErrorMessage("Exception while chain import: " + e.getMessage());
        }

        return importChainPreview;
    }

    private void collectUsedSystemIds(List<ChainElementExternalEntity> elementExternalEntities, Set<String> usedSystemIds) {
        for (ChainElementExternalEntity elementExternalEntity : elementExternalEntities) {
            if (CollectionUtils.isNotEmpty(elementExternalEntity.getChildren())) {
                collectUsedSystemIds(elementExternalEntity.getChildren(), usedSystemIds);
            }

            String integrationSystemId = (String) elementExternalEntity.getProperties().get(SYSTEM_ID);
            if (integrationSystemId != null) {
                usedSystemIds.add(integrationSystemId);
            }
        }
    }

    private ImportChainsAndInstructionsResult restoreChainsFromDir(
            File importDirectory,
            ArrayList<ChainCommitRequest> commitRequests,
            String importId,
            Set<String> technicalLabels,
            boolean validateByHash
    ) {
        List<ImportChainResult> importChainResults = new ArrayList<>();

        importDirectory = new File(importDirectory, CHAINS_ARCH_PARENT_DIR);
        File[] chainFilesDirectories = importDirectory.listFiles(File::isDirectory);
        if (chainFilesDirectories == null) {
            return new ImportChainsAndInstructionsResult();
        }

        ChainsIgnoreOverrideResult chainsIgnoreOverrideResult = importInstructionsService
                .performChainIgnoreOverrideInstructions(Stream.of(chainFilesDirectories).map(File::getName).collect(Collectors.toSet()));
        Map<String, ChainCommitRequestAction> chainsToImport = chainsIgnoreOverrideResult.chainsToImport();
        Map<String, String> overridesMapping = chainsIgnoreOverrideResult.overridesMapping();
        int total = chainFilesDirectories.length;
        int counter = 0;
        for (File chainFilesDir : chainFilesDirectories) {
            String chainId = chainFilesDir.getName();
            if (!chainsToImport.containsKey(chainId)) {
                importChainResults.add(ImportChainResult.builder()
                        .id(chainId)
                        .status(ImportEntityStatus.IGNORED)
                        .deployAction(ChainCommitRequestAction.NONE)
                        .build());
                log.info("Chain {} ignored as a part of import instructions list", chainId);
                continue;
            }

            Pair<String, String> overridesPair = overridesMapping.entrySet().stream()
                    .filter(pair -> chainId.equals(pair.getKey()) || chainId.equals(pair.getValue()))
                    .findFirst()
                    .map(pair -> Pair.of(pair.getKey(), pair.getValue()))
                    .orElse(null);

            importProgressService.calculateImportStatus(
                    importId, total, counter, ImportSessionService.SERVICE_IMPORT_PERCENTAGE_THRESHOLD, ImportSessionService.CHAIN_IMPORT_PERCENTAGE_THRESHOLD);
            counter++;

            ImportChainResult chainResult = restoreOneChainFromDir(chainFilesDir, commitRequests, technicalLabels, overridesPair, validateByHash);

            if (chainResult != null) {
                importChainResults.add(chainResult);

                ChainCommitRequestAction deployAction = chainsToImport.get(chainId);
                if (deployAction != null) {
                    commitRequests.stream()
                            .filter(commitRequest -> chainId.equals(commitRequest.getId()))
                            .findFirst()
                            .ifPresentOrElse(
                                    commitRequest -> commitRequest.setDeployAction(deployAction),
                                    () -> commitRequests.add(ChainCommitRequest.builder()
                                            .id(chainId)
                                            .deployAction(deployAction)
                                            .build()));
                }
            }
        }

        return new ImportChainsAndInstructionsResult(importChainResults, chainsIgnoreOverrideResult.chainImportInstructionResults());
    }

    private ImportChainResult restoreOneChainFromDir(
            File chainFilesDir,
            List<ChainCommitRequest> commitRequests,
            Set<String> technicalLabels,
            Pair<String, String> overridesPair,
            boolean validateByHash
    ) {
        ImportChainResult importChainResult = null;
        ChainExternalEntity chainExternalEntity = null;
        try {
            String chainYAML = Files.readString(getChainYAMLFile(chainFilesDir).toPath());
            chainYAML = migrateToActualFileVersion(chainYAML);
            chainExternalEntity = yamlMapper.readValue(chainYAML, ChainExternalEntity.class);

            String chainId = chainExternalEntity.getId();
            String externalHash = ChainUtils.getChainFilesHash(chainFilesDir, artifactDescriptorVersion);

            if (isSameHash(chainId, externalHash) && validateByHash) {
                log.warn(CHAINS_HASH_MATCHED_LOG_MESSAGE, chainId);
                importChainResult = new ImportChainResult();
                importChainResult.setId(chainId);
                importChainResult.setName(chainExternalEntity.getName());
                importChainResult.setStatus(ImportEntityStatus.SKIPPED);
                return importChainResult;
            }

            if (shouldCommitChain(commitRequests, chainId)) {
                if (overridesPair != null) {
                    technicalLabels = technicalLabels != null ? new HashSet<>(technicalLabels) : new HashSet<>();
                    if (chainId.equals(overridesPair.getKey())) {
                        chainExternalEntity.getContent().setOverridesChainId(overridesPair.getValue());
                        technicalLabels.add(OVERRIDES_LABEL_NAME);
                    }
                    if (chainId.equals(overridesPair.getValue())) {
                        chainExternalEntity.getContent().setOverridden(true);
                        chainExternalEntity.getContent().setOverriddenByChainId(overridesPair.getKey());
                        technicalLabels.add(OVERRIDDEN_LABEL_NAME);
                    }
                }
                chainExternalEntity.getContent().setLastImportHash(externalHash);
                importChainResult = saveChainInTransaction(chainExternalEntity, chainFilesDir, technicalLabels);
            }
        } catch (ChainImportException e) {
            log.warn("Exception while importing {} ({}) chain: ", e.getChainName(), e.getChainId(), e);
            importChainResult = new ImportChainResult();
            importChainResult.setStatus(ImportEntityStatus.ERROR);
            importChainResult.setId(e.getChainId());
            importChainResult.setName(e.getChainName());
            importChainResult.setErrorMessage(e.getMessage());
        } catch (Exception e) {
            log.warn("Exception while chain import: ", e);
            importChainResult = new ImportChainResult();
            if (chainExternalEntity != null) {
                importChainResult.setId(chainExternalEntity.getId());
                importChainResult.setName(chainExternalEntity.getName());
            }
            importChainResult.setStatus(ImportEntityStatus.ERROR);
            importChainResult.setErrorMessage("Exception while chain import: " + e.getMessage());
        }
        return importChainResult;
    }

    private boolean shouldCommitChain(List<ChainCommitRequest> commitRequests, String chainId) {
        return CollectionUtils.isEmpty(commitRequests) || commitRequests.stream().anyMatch(request -> Objects.equals(request.getId(), chainId));
    }

    private boolean isSameHash(String chainId, String externalHash) {
        String internalHash = chainService.getChainHash(chainId);
        return externalHash.equals(internalHash);
    }

    private ImportChainResult saveChainInTransaction(ChainExternalEntity chainExternalEntity, File chainFilesDir, Set<String> technicalLabels) {
        return transactionTemplate.execute(status -> saveImportedChain(chainExternalEntity, chainFilesDir, technicalLabels));
    }

    public ImportChainResult saveImportedChain(ChainExternalEntity chainExternalEntity, File chainFilesDir, Set<String> technicalLabels) {
        Chain currentChainState = chainService.tryFindById(chainExternalEntity.getId()).orElse(null);
        ImportEntityStatus importStatus = currentChainState != null ? ImportEntityStatus.UPDATED : ImportEntityStatus.CREATED;

        Folder existingFolder = null;
        if (chainExternalEntity.getContent().getFolder() != null) {
            existingFolder = folderService.findFirstByName(chainExternalEntity.getContent().getFolder().getName(), null);
        }

        Chain newChainState = chainExternalEntityMapper.toInternalEntity(ChainExternalMapperEntity.builder()
                .chainExternalEntity(chainExternalEntity)
                .existingChain(currentChainState)
                .existingFolder(existingFolder)
                .chainFilesDirectory(chainFilesDir)
                .build());

        replaceTechnicalLabels(technicalLabels, newChainState);

        newChainState.setUnsavedChanges(true);

        setActualChainState(existingFolder, currentChainState, newChainState);

        logImportAction(newChainState, LogOperation.CREATE_OR_UPDATE);

        chainService.clearContext();

        return ImportChainResult.builder()
                .id(newChainState.getId())
                .name(newChainState.getName())
                .deployAction(chainExternalEntity.getContent().getDeployAction())
                .deployments(chainExternalEntity.getContent().getDeployments())
                .status(importStatus)
                .build();
    }

    public static void replaceTechnicalLabels(Set<String> technicalLabels, Chain chain) {
        if (!CollectionUtils.isEmpty(technicalLabels)) {
            // Remove absent labels from db
            chain.getLabels().removeIf(label -> label.isTechnical() && !technicalLabels.contains(label.getName()));
            // Add to database only missing labels
            Set<String> currentChainTechnicalLabels = chain.getLabels().stream().filter(ChainLabel::isTechnical).map(ChainLabel::getName).collect(Collectors.toSet());
            Set<String> technicalLabelsToAdd = technicalLabels.stream().filter(labelName -> !currentChainTechnicalLabels.contains(labelName)).collect(Collectors.toSet());

            technicalLabelsToAdd.forEach(labelName -> chain.addLabel(new ChainLabel(labelName, chain, true)));
        } else {
            chain.getLabels().removeIf(ChainLabel::isTechnical);
        }
    }

    protected String migrateToActualFileVersion(String fileContent) throws Exception {
        return chainFileMigrationService.migrateToActualVersion(fileContent);
    }

    private File getChainYAMLFile(File chainDir) {
        File[] chainFiles = chainDir.listFiles((dir, fileName) ->
                fileName.startsWith(CHAIN_YAML_NAME_PREFIX) && fileName.endsWith(YAML_FILE_NAME_POSTFIX));

        if (ArrayUtils.isEmpty(chainFiles)) {
            throw new RuntimeException("Directory " + chainDir.getName() + " does not contain chain YAML file");
        }

        return chainFiles[0];
    }

    private void makeDeployActions(List<ImportChainResult> chainsResult, List<ChainCommitRequest> commitRequests, String importId, Set<String> technicalLabels) {
        List<ChainDeployPrepare> preparedDeployments = new ArrayList<>();
        int total = chainsResult.size();
        int counter = 0;
        for (ImportChainResult chainResult : chainsResult) {
            importProgressService.calculateImportStatus(
                    importId, total, counter, ImportSessionService.CHAIN_IMPORT_PERCENTAGE_THRESHOLD, ImportSessionService.SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD);
            counter++;

            if (chainResult.getStatus() == ImportEntityStatus.ERROR
                    || chainResult.getStatus() == ImportEntityStatus.IGNORED) {
                continue;
            }

            ChainCommitRequest request = null;
            if (!CollectionUtils.isEmpty(commitRequests)) {
                request = commitRequests.stream()
                        .filter(cRequest -> StringUtils.equals(cRequest.getId(), chainResult.getId()))
                        .findAny()
                        .orElse(null);
                if (request == null || request.getDeployAction() == ChainCommitRequestAction.NONE) {
                    continue;
                }
            }

            try {
                Snapshot snapshot = snapshotService.build(chainResult.getId(), technicalLabels);
                if (request != null) {
                    if (request.getDeployAction() == ChainCommitRequestAction.SNAPSHOT) {
                        continue;
                    }
                    if (request.getDeployAction() == ChainCommitRequestAction.DEPLOY && CollectionUtils.isNotEmpty(request.getDomains())) {
                        List<DeploymentExternalEntity> deployments = request.getDomains().stream()
                                .map(domain -> DeploymentExternalEntity.builder().domain(domain.getName()).build())
                                .collect(Collectors.toList());
                        chainResult.setDeployments(deployments);
                    }
                }
                if (CollectionUtils.isNotEmpty(chainResult.getDeployments())) {
                    preparedDeployments.add(new ChainDeployPrepare(chainResult, snapshot));
                }
            } catch (Exception e) {
                chainResult.setStatus(ImportEntityStatus.ERROR);
                chainResult.setErrorMessage(SAVED_WITHOUT_SNAPSHOT_ERROR_MESSAGE + e.getMessage());
            }
        }

        deployChains(preparedDeployments, importId);
    }

    private void deployChains(List<ChainDeployPrepare> chainsToDeploy, String importId) {
        List<Deployment> oldDeploysList = new ArrayList<>();
        int total = chainsToDeploy.size();
        int counter = 0;

        for (ChainDeployPrepare entity : chainsToDeploy) {
            String chainId = entity.getImportChainResult().getId();
            oldDeploysList.addAll(deploymentService.findAllByChainId(chainId));
        }
        for (ChainDeployPrepare entity : chainsToDeploy) {
            importProgressService.calculateImportStatus(importId, total, counter, ImportSessionService.SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD, 100);
            counter++;

            ImportChainResult importChainResult = entity.getImportChainResult();
            Snapshot snapshot = entity.getSnapshot();
            List<DeploymentExternalEntity> deployments = importChainResult.getDeployments();

            if (!CollectionUtils.isEmpty(deployments)) {
                try {
                    for (DeploymentExternalEntity deployment : deployments) {
                        if (engineService.isDevMode() || engineService.getDomainByName(deployment.getDomain()) != null) {
                            createDeployment(snapshot, oldDeploysList, deployment);
                        } else {
                            importChainResult.setStatus(ImportEntityStatus.ERROR);
                            importChainResult.setErrorMessage(
                                    SAVED_WITHOUT_DEPLOYMENT_ERROR_MESSAGE + "domain "
                                            + deployment.getDomain() + " doesn't exists");
                        }
                    }
                } catch (Exception e) {
                    log.error("Unable to deploy chain {} {}", importChainResult.getId(),
                            e.getMessage());
                    importChainResult.setStatus(ImportEntityStatus.ERROR);
                    importChainResult.setErrorMessage(
                            SAVED_WITHOUT_DEPLOYMENT_ERROR_MESSAGE + e.getMessage());
                }
            }
        }
    }

    private void createDeployment(Snapshot snapshot, List<Deployment> excludeDeployments, DeploymentExternalEntity deployment) {
        Deployment deploymentConfig = new Deployment();
        deploymentConfig.setDomain(deployment.getDomain());
        deploymentService.create(deploymentConfig, snapshot.getChain(), snapshot, excludeDeployments);
    }

    private void setActualChainState(Folder currentFolder, Chain currentChainState, Chain newChainState) {
        //Actualize new entities state in persistence context
        //Dependencies
        dependencyService.setActualizedElementDependencyStates(
                currentChainState != null ? currentChainState.getDependencies() : Collections.emptySet(),
                newChainState.getDependencies());

        //Chain Elements
        elementService.setActualizedChainElements(
                currentChainState != null ? currentChainState.getElements() : Collections.emptyList(),
                newChainState.getElements()
        );

        //Masked Field
        maskedFieldsService.setActualizedMaskedFields(
                currentChainState != null ? currentChainState.getMaskedFields() : Collections.emptySet(),
                newChainState.getMaskedFields()
        );

        //Chain
        chainService.setActualizedChainState(currentChainState, newChainState);

        //Chain Folder
        if (currentFolder == null && newChainState.getParentFolder() != null) {
            newChainState.setParentFolder(folderService.setActualizedFolderState(newChainState.getParentFolder()));
        }
    }

    private void logImportAction(@NonNull Chain chain, LogOperation operation) {
        actionsLogService.logAction(ActionLog.builder()
                .entityType(EntityType.CHAINS)
                .entityName(chain.getName())
                .operation(operation)
                .entityId(chain.getId())
                .parentType(chain.getParentFolder() == null ? null : EntityType.FOLDER)
                .parentId(chain.getParentFolder() == null ? null : chain.getParentFolder().getId())
                .parentName(chain.getParentFolder() == null ? null : chain.getParentFolder().getName())
                .build());
    }

    /**
     * Used by {@link ImportService#importFile} for backward capability with import V1 API
     */
    @Deprecated(since = "2023.4")
    public List<ImportChainResult> restoreChainsFromDirBackward(File importDirectory, List<ChainCommitRequest> commitRequests, String importId, Set<String> technicalLabels) {
        return restoreChainsFromDir(importDirectory, new ArrayList<>(commitRequests), importId, technicalLabels, false).chainResults();
    }

    /**
     * Used by {@link ImportService#restoreChainFromYaml} for backward capability with import V1 V2 API
     */
    @Deprecated(since = "2023.4")
    public void saveImportedChainBackward(Chain importedChain) {
        Chain currentChainState = chainService.tryFindById(importedChain.getId()).orElse(null);
        Folder existingFolder = null;
        if (importedChain.getParentFolder() != null) {
            existingFolder = folderService.findEntityByIdOrNull(importedChain.getParentFolder().getId());
        }
        if (currentChainState != null) {
            ChainUtils.chainPropertiesInitialization(currentChainState);
        }
        setActualChainState(existingFolder, currentChainState, importedChain);
    }
}
