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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.context.RequestIdContext;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog.ActionLogBuilder;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportChainPreviewDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportPreviewDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.engine.ImportDomainDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.ChainImportException;
import org.qubership.integration.platform.runtime.catalog.service.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ChainDeployPrepare;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ChainDeserializationResult;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ChainFileMigrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.SYSTEM_ID;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;

/**
 * @deprecated 23.4 use {@link GeneralImportService} instead
 */
@Slf4j
@Service
@Deprecated(since = "2023.4")
public class ImportService {

    private final ChainExternalEntityMapper chainExternalEntityMapper;
    private final YAMLMapper yamlMapper;
    private final ObjectMapper objectMapper;
    protected final ActionsLogService actionLogger;
    private final DeploymentService deploymentService;
    protected final SnapshotService snapshotService;
    private final EngineService engineService;
    private final ChainService chainService;
    private final FolderService folderService;
    private final ImportSessionService importProgressService;
    private final ChainImportService chainImportService;
    protected final ChainRepository chainRepository;
    private final TransactionTemplate transactionTemplate;
    private final ChainFileMigrationService chainFileMigrationService;

    private static final short ASYNC_IMPORT_PERCENTAGE_THRESHOLD = 40;
    private static final short ASYNC_SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD = 90;

    @Autowired
    public ImportService(ChainExternalEntityMapper chainExternalEntityMapper,
                         YAMLMapper yamlMapper,
                         @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
                         ActionsLogService actionLogger,
                         DeploymentService deploymentService,
                         SnapshotService snapshotService,
                         EngineService engineService,
                         ChainService chainService,
                         FolderService folderService,
                         ChainRepository chainRepository,
                         ImportSessionService importProgressService,
                         ChainImportService chainImportService,
                         TransactionTemplate transactionTemplate,
                         ChainFileMigrationService chainFileMigrationService
    ) {
        this.chainExternalEntityMapper = chainExternalEntityMapper;
        this.objectMapper = objectMapper;
        this.yamlMapper = yamlMapper;
        this.actionLogger = actionLogger;
        this.deploymentService = deploymentService;
        this.snapshotService = snapshotService;
        this.engineService = engineService;
        this.chainService = chainService;
        this.folderService = folderService;
        this.chainRepository = chainRepository;
        this.importProgressService = importProgressService;
        this.chainImportService = chainImportService;
        this.transactionTemplate = transactionTemplate;
        this.chainFileMigrationService = chainFileMigrationService;
    }

    public ImportPreviewDTO importFileAsPreview(MultipartFile file) {
        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (log.isDebugEnabled()) {
            log.debug("File has '{}' extension", fileExtension);
        }
        ImportPreviewDTO response = new ImportPreviewDTO();

        if (ZIP_EXTENSION.equals(fileExtension)) {
            try (InputStream is = file.getInputStream()) {
                response = importZIPAsPreview(is);
            } catch (Exception e) {
                log.warn("Exception while extract files from zip: ", e);
                response.setErrorMessage(
                        "Exception while extract files from zip: " + e.getMessage());
                return response;
            }
        } else if (YAML_EXTENSION.equals(fileExtension)) {
            try {
                ImportChainPreviewDTO previewChainDTO = restoreChainPreviewFromYaml(
                        getFileContent(file));
                response.setChains(Collections.singletonList(previewChainDTO));
            } catch (Exception e) {
                log.warn("Exception while chain import: ", e);
                response.setErrorMessage("Exception while chain import: " + e.getMessage());
            }
        } else {
            log.warn("Unknown type of file: {}", file.getOriginalFilename());
            response.setErrorMessage("Unknown type of file: " + file.getOriginalFilename());
        }
        return response;
    }

    public ImportChainPreviewDTO restoreChainPreviewFromDir(File chainFilesDir) {
        ImportChainPreviewDTO resultImportChainPreviewDTO;
        try {
            String chainYAML = Files.readString(getChainYAMLFile(chainFilesDir).toPath());
            resultImportChainPreviewDTO = restoreChainPreviewFromYaml(chainYAML);
        } catch (Exception e) {
            log.warn("Exception while chain import: ", e);
            resultImportChainPreviewDTO = new ImportChainPreviewDTO();
            resultImportChainPreviewDTO.setErrorMessage(
                    "Exception while chain import: " + e.getMessage());
        }
        return resultImportChainPreviewDTO;
    }

    public ImportChainPreviewDTO restoreChainPreviewFromYaml(String yaml) {
        ImportChainPreviewDTO resultImportChainPreviewDTO;
        Set<String> usedSystems = Collections.emptySet();

        try {
            yaml = migrateToActualFileVersion(yaml);
            resultImportChainPreviewDTO = yamlMapper.readValue(yaml, ImportChainPreviewDTO.class);
            JsonNode elementsNode = yamlMapper.readTree(yaml).get("elements");
            if (elementsNode != null) {
                usedSystems = getUsedSystemIdsFromNode(elementsNode);
            }
            resultImportChainPreviewDTO.setUsedSystems(usedSystems);

            ChainCommitRequestAction commitAction =
                    CollectionUtils.isEmpty(resultImportChainPreviewDTO.getDeployments())
                            ? ChainCommitRequestAction.SNAPSHOT : ChainCommitRequestAction.DEPLOY;
            resultImportChainPreviewDTO.setDeployAction(commitAction);
        } catch (ChainImportException e) {
            log.warn("Error while chain import: ", e);
            resultImportChainPreviewDTO = new ImportChainPreviewDTO();
            resultImportChainPreviewDTO.setId(e.getChainId());
            resultImportChainPreviewDTO.setName(e.getChainName());
            resultImportChainPreviewDTO.setErrorMessage(e.getMessage());
        } catch (Exception e) {
            log.warn("Exception while chain import: ", e);
            resultImportChainPreviewDTO = new ImportChainPreviewDTO();
            resultImportChainPreviewDTO.setErrorMessage(
                    "Exception while chain import: " + e.getMessage());
        }
        return resultImportChainPreviewDTO;
    }

    protected Set<String> getUsedSystemIdsFromNode(JsonNode elementsNode) {
        Set<String> usedSystems = new HashSet<>();
        for (final JsonNode element : elementsNode) {
            JsonNode children = element.get("children");
            if (children != null) {
                usedSystems.addAll(getUsedSystemIdsFromNode(children));
            }

            JsonNode properties = element.get("properties");
            if (properties == null) {
                continue;
            }
            JsonNode integrationSystemId = properties.get(SYSTEM_ID);
            if (integrationSystemId != null && !StringUtils.isBlank(integrationSystemId.asText())) {
                usedSystems.add(integrationSystemId.asText());
            }
        }
        return usedSystems;
    }

    public String importFileAsync(MultipartFile file, List<ChainCommitRequest> commitRequests, Set<String> technicalLabels) {
        String importId = UUID.randomUUID().toString();

        importProgressService.deleteObsoleteImportSessionStatuses();
        importProgressService.setImportProgressPercentage(importId, 0);

        File unpackDirectory = null;
        try (InputStream is = file.getInputStream()) {
            unpackDirectory = unpackZIP(is);
        } catch (Exception e) {
            ExportImportUtils.deleteFile(unpackDirectory);
            log.warn("Exception while extract files from zip", e);
            throw new RuntimeException("Exception while extract files from zip", e);
        }

        logImportAction(null, file.getOriginalFilename(), LogOperation.IMPORT);
        String requestId = RequestIdContext.get();
        File finalUnpackDirectory = unpackDirectory;
        CompletableFuture.supplyAsync(() -> {
                    RequestIdContext.set(requestId);
                    ImportDTO response = restoreChainsFromDir(finalUnpackDirectory, commitRequests, importId, technicalLabels);
                    makeDeployActions(response.getChains(), commitRequests, importId, technicalLabels);
                    return response;
                })
                .whenCompleteAsync((result, throwable) -> {
                    RequestIdContext.set(requestId);
                    importAsyncComplete(importId, result, finalUnpackDirectory, throwable);
                });
        return importId;
    }

    private void importAsyncComplete(String importId, ImportDTO importResult, File unpackDirectory, Throwable throwable) {
        ExportImportUtils.deleteFile(unpackDirectory);
        ImportSession importSession = new ImportSession();
        importSession.setId(importId);
        importSession.setCompletion(100);
        if (importResult != null) {
            List<ImportChainResult> importChainResults = importResult.getChains().stream()
                    .map(chainDTO -> ImportChainResult.builder()
                            .id(chainDTO.getId())
                            .name(chainDTO.getName())
                            .deployAction(chainDTO.getDeployAction())
                            .status(chainDTO.getStatus())
                            .deployments(chainDTO.getDeployments())
                            .errorMessage(chainDTO.getErrorMessage())
                            .build())
                    .collect(Collectors.toList());
            importSession.setResult(ImportResult.builder().chains(importChainResults).build());
            String errorMessage = importResult.getErrorMessage();
            if (!StringUtils.isBlank(errorMessage)) {
                importSession.setError(errorMessage);
                log.warn("Error async importing file {}", errorMessage);
            }
        }
        if (throwable != null) {
            importSession.setError(throwable.getMessage());
            log.warn("Error async importing file", throwable);
        }

        importProgressService.saveImportSession(importSession);
    }

    @Nullable
    public List<ImportChainResult> getImportAsyncResult(String importId) {
        ImportSession importSession = importProgressService.getImportSession(importId);

        if (importSession != null && !StringUtils.isBlank(importSession.getError())) {
            throw new RuntimeException(importSession.getError());
        }

        return importSession == null || importSession.getResult() == null
                ? null
                : importSession.getResult().getChains();
    }

    public ImportDTO importFile(MultipartFile file, List<ChainCommitRequest> commitRequests, Set<String> technicalLabels) {
        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (log.isDebugEnabled()) {
            log.debug("File has '{}' extension", fileExtension);
        }
        ImportDTO response = new ImportDTO();
        if (ZIP_EXTENSION.equals(fileExtension)) {
            File unpackDirectory = null;
            try (InputStream is = file.getInputStream()) {
                unpackDirectory = unpackZIP(is);
                logImportAction(null, file.getOriginalFilename(), LogOperation.IMPORT);
                response.setChains(chainImportService.restoreChainsFromDirBackward(unpackDirectory, commitRequests, null, technicalLabels));
                makeDeployActions(response.getChains(), commitRequests, null, technicalLabels);
            } catch (Exception e) {
                log.warn("Exception while extract files from zip: ", e);
                response.setErrorMessage(
                        "Exception while extract files from zip: " + e.getMessage());
            } finally {
                ExportImportUtils.deleteFile(unpackDirectory);
            }
        } else if (YAML_EXTENSION.equals(fileExtension)) {
            try {
                logImportAction(null, file.getOriginalFilename(), LogOperation.IMPORT);
                ImportChainResult importChainDTO = restoreChainFromYaml(getFileContent(file), null,
                        null, technicalLabels);
                response.setChains(makeDeployActions(importChainDTO, commitRequests, technicalLabels));
            } catch (Exception e) {
                log.warn("Exception while chain import: ", e);
                response.setErrorMessage("Exception while chain import: " + e.getMessage());
            }
        } else {
            log.warn("Unknown type of file: {}", file.getOriginalFilename());
            response.setErrorMessage("Unknown type of file: " + file.getOriginalFilename());
        }

        return response;
    }

    private List<ImportChainResult> makeDeployActions(ImportChainResult chainDTO,
                                                      List<ChainCommitRequest> commitRequests,
                                                      Set<String> technicalLabels) {
        return makeDeployActions(Collections.singletonList(chainDTO), commitRequests, null, technicalLabels);
    }

    private List<ImportChainResult> makeDeployActions(List<ImportChainResult> chainDTOs,
                                                      List<ChainCommitRequest> commitRequests,
                                                      String importId,
                                                      Set<String> technicalLabels) {
        List<ChainDeployPrepare> preparedDeployments = new ArrayList<>();
        int total = chainDTOs.size();
        int counter = 0;
        for (ImportChainResult chainDTO : chainDTOs) {
            calculateSnapshotAsyncStatus(importId, total, counter);
            counter++;

            if (chainDTO.getStatus() == ImportEntityStatus.ERROR) {
                continue;
            }

            ChainCommitRequest request = null;
            if (!CollectionUtils.isEmpty(commitRequests)) {
                request = commitRequests.stream()
                        .filter(creq -> creq.getId().equals(chainDTO.getId())).findAny().orElse(null);
                if (request == null || request.getDeployAction() == ChainCommitRequestAction.NONE) {
                    continue;
                }
            }

            try {
                Snapshot snapshot = snapshotService.build(chainDTO.getId(), technicalLabels);
                mergeDeploymentInfo(chainDTO, request);
                if (chainDTO.getDeployAction() == ChainCommitRequestAction.DEPLOY) {
                    if (!CollectionUtils.isEmpty(chainDTO.getDeployments())) {
                        preparedDeployments.add(new ChainDeployPrepare(chainDTO, snapshot));
                    }
                }
            } catch (Exception e) {
                chainDTO.setStatus(ImportEntityStatus.ERROR);
                chainDTO.setErrorMessage(SAVED_WITHOUT_SNAPSHOT_ERROR_MESSAGE + e.getMessage());
            }
        }

        deployChains(preparedDeployments, importId);

        return chainDTOs;
    }

    private void mergeDeploymentInfo(ImportChainResult chainDTO, ChainCommitRequest apiRequest) {
        chainDTO.setDeployAction(getResultingDeployAction(chainDTO, apiRequest));
        if (!ChainCommitRequestAction.DEPLOY.equals(chainDTO.getDeployAction())) {
            chainDTO.setDeployments(Collections.emptyList());
            return;
        }

        mergeLoggingInfo(chainDTO, apiRequest);
    }

    private void mergeLoggingInfo(ImportChainResult chainDTO, ChainCommitRequest apiRequest) {
        if (apiRequest == null || CollectionUtils.isEmpty(apiRequest.getDomains())) {
            return;
        }
        if (chainDTO.getDeployments() == null) {
            chainDTO.setDeployments(new ArrayList<>());
        }

        for (ImportDomainDTO requestDomain : apiRequest.getDomains()) {
            if (chainDTO.getDeployments().stream().anyMatch(d -> d.getDomain().equals(requestDomain.getName()))) {
                continue;
            }

            chainDTO.getDeployments().add(DeploymentExternalEntity.builder().domain(requestDomain.getName()).build());
        }
    }

    private ChainCommitRequestAction getResultingDeployAction(ImportChainResult chainDTO, ChainCommitRequest apiRequest) {
        if (apiRequest != null) {
            if (apiRequest.getDeployAction() != null) {
                return apiRequest.getDeployAction();
            }
        }
        if (chainDTO.getDeployAction() != null) {
            return chainDTO.getDeployAction();
        }
        return ChainCommitRequestAction.SNAPSHOT;
    }

    public ImportChainResult restoreChainFromYaml(String yaml, File chainFilesDir,
                                               List<ChainCommitRequest> commitRequests,
                                               Set<String> technicalLabels) {
        ImportChainResult resultImportChainDTO = null;
        ChainCompareDTO basicChainInfo = null;
        try {
            String migratedYaml = migrateToActualFileVersion(yaml);
            basicChainInfo = getYamlBasicChainInfo(migratedYaml);
            ChainCompareDTO finalBasicChainInfo = basicChainInfo;
            if (CollectionUtils.isEmpty(commitRequests)
                    || commitRequests.stream()
                            .anyMatch(request -> request.getId().equals(finalBasicChainInfo.getId()))) {

                ChainExternalEntity chainExternalEntity = yamlMapper.readValue(migratedYaml, ChainExternalEntity.class);
                Chain currentChainState = chainService.tryFindById(chainExternalEntity.getId()).orElse(null);
                ImportEntityStatus importStatus = currentChainState != null ? ImportEntityStatus.UPDATED : ImportEntityStatus.CREATED;
                Folder existingFolder = null;
                if (chainExternalEntity.getContent().getFolder() != null) {
                    existingFolder = folderService.findFirstByName(chainExternalEntity.getContent().getFolder().getName(), null);
                }
                Chain chain = chainExternalEntityMapper.toInternalEntity(ChainExternalMapperEntity.builder()
                        .chainExternalEntity(chainExternalEntity)
                        .existingChain(currentChainState)
                        .existingFolder(existingFolder)
                        .chainFilesDirectory(chainFilesDir)
                        .build());

                ChainImportService.replaceTechnicalLabels(technicalLabels, chain);

                chain.setUnsavedChanges(true);

                resultImportChainDTO = ImportChainResult.builder()
                        .id(chain.getId())
                        .name(chain.getName())
                        .status(importStatus)
                        .deployAction(chainExternalEntity.getContent().getDeployAction())
                        .deployments(chainExternalEntity.getContent().getDeployments())
                        .build();

                Timestamp modificationTimestamp = chain.getModifiedWhen();

                chain.setModifiedWhen(modificationTimestamp);
                chainImportService.saveImportedChainBackward(chain);

                logImportAction(chain, null, LogOperation.CREATE_OR_UPDATE);
            }
        } catch (ChainImportException e) {
            log.warn("Exception while importing {} ({}) chain: ", e.getChainName(), e.getChainId(), e);
            resultImportChainDTO = new ImportChainResult();
            resultImportChainDTO.setStatus(ImportEntityStatus.ERROR);
            resultImportChainDTO.setId(e.getChainId());
            resultImportChainDTO.setName(e.getChainName());
            resultImportChainDTO.setErrorMessage(e.getMessage());
        } catch (Exception e) {
            log.warn("Exception while chain import: ", e);
            resultImportChainDTO = new ImportChainResult();
            if (basicChainInfo != null) {
                resultImportChainDTO.setId(basicChainInfo.getId());
                resultImportChainDTO.setName(basicChainInfo.getName());
            }
            resultImportChainDTO.setStatus(ImportEntityStatus.ERROR);
            resultImportChainDTO.setErrorMessage("Exception while chain import: " + e.getMessage());
        }
        return resultImportChainDTO;
    }

    private void logImportAction(Chain chain, String archiveName, LogOperation operation) {
        ActionLogBuilder builder = ActionLog.builder()
                .entityType(chain != null ? EntityType.CHAIN : EntityType.CHAINS)
                .entityName(chain != null ? chain.getName() : archiveName)
                .operation(operation);
        if (chain != null) {
            builder
                    .entityId(chain.getId())
                    .parentType(chain.getParentFolder() == null ? null : EntityType.FOLDER)
                    .parentId(chain.getParentFolder() == null ? null : chain.getParentFolder().getId())
                    .parentName(chain.getParentFolder() == null ? null : chain.getParentFolder().getName());
        }
        actionLogger.logAction(builder.build());
    }

    private synchronized ChainDeserializationResult deserializeChain(String yaml, File chainFilesDir)
            throws IOException {
        ChainDeserializationResult chainDeserializationResult = yamlMapper.readValue(yaml,
                ChainDeserializationResult.class);
        Chain chain = chainDeserializationResult.getChain();

        chain.setUnsavedChanges(true);
        checkAndPutScriptFileNameInServiceCall(chain, chainDeserializationResult);

        if (!CollectionUtils.isEmpty(chainDeserializationResult.getPropertiesFileNames())) {
            restorePropertiesFromFiles(chain, chainFilesDir,
                    chainDeserializationResult.getPropertiesFileNames());
        }

        return chainDeserializationResult;
    }

    private void checkAndPutScriptFileNameInServiceCall(Chain chain, ChainDeserializationResult chainDeserializationResult) {
        List<ChainElement> chainElements = chain.getElements().stream().filter(element -> element.getType().equals(SERVICE_CALL))
                .collect(Collectors.toList());
        for (ChainElement chainElement : chainElements) {
            List<Map<String, Object>> afterPropertyList = (List<Map<String, Object>>) chainElement.getProperties().get(AFTER);
            if (!CollectionUtils.isEmpty(afterPropertyList)) {
                for (Map<String, Object> afterPropMap : afterPropertyList) {
                    String afterScriptFileName = !CollectionUtils.isEmpty(afterPropMap) ? (String) afterPropMap.get(FILE_NAME_PROPERTY) : null;
                    if (null != afterScriptFileName) {
                        Map fileNameMap = chainDeserializationResult.getPropertiesFileNames();
                        if (fileNameMap.get(chainElement.getId()) != null) {
                            afterScriptFileName = fileNameMap.get(chainElement.getId()) + SCRIPT_SEPARATOR + afterScriptFileName;
                        }
                        fileNameMap.put(chainElement.getId(), afterScriptFileName);
                    }
                }
            }
            Map beforePropMap = (Map) chainElement.getProperties().get(BEFORE);
            if (!CollectionUtils.isEmpty(beforePropMap)) {
                String beforeScriptFileName = (String) beforePropMap.get(FILE_NAME_PROPERTY);
                if (null != beforeScriptFileName) {
                    Map fileNameMap = chainDeserializationResult.getPropertiesFileNames();
                    String afterScriptFileName = (String) fileNameMap.get(chainElement.getId());
                    fileNameMap.put(chainElement.getId(), afterScriptFileName != null ? afterScriptFileName + SCRIPT_SEPARATOR + beforeScriptFileName
                            : beforeScriptFileName);
                }
            }
        }
    }

    public ImportChainResult restoreChainFromDir(File chainFilesDir,
                                              List<ChainCommitRequest> commitRequests,
                                              Set<String> technicalLabels) {
        ImportChainResult resultImportChainDTO;
        try {
            String chainYAML = Files.readString(getChainYAMLFile(chainFilesDir).toPath());
            resultImportChainDTO = transactionTemplate.execute((status -> restoreChainFromYaml(chainYAML, chainFilesDir, commitRequests, technicalLabels)));
        } catch (Exception e) {
            log.warn("Exception while chain import: ", e);
            resultImportChainDTO = new ImportChainResult();
            resultImportChainDTO.setStatus(ImportEntityStatus.ERROR);
            resultImportChainDTO.setErrorMessage("Exception while chain import: " + e.getMessage());
        }
        return resultImportChainDTO;
    }

    private void restorePropertiesFromFiles(Chain deserializedChain, File chainFilesDir,
                                            Map<String, String> propertiesFileNames) throws IOException {

        for (ChainElement element : deserializedChain.getElements()) {
            String propertiesFileName = propertiesFileNames.get(element.getId());
            if (propertiesFileName != null) {
                if (propertiesFileName.contains(SCRIPT_SEPARATOR)) {
                    StringTokenizer st = new StringTokenizer(propertiesFileName, SCRIPT_SEPARATOR);
                    while (st.hasMoreTokens()) {
                        restoreProperties(element, chainFilesDir, st.nextToken());
                    }
                } else {
                    restoreProperties(element, chainFilesDir, propertiesFileName);
                }
            }
        }
    }

    private void restoreProperties(ChainElement element,
                                   File chainFilesDir,
                                   String propertiesFilename) throws IOException {

        if (ExportImportUtils.isPropertiesFileJson(element.getProperties()) || propertiesFilename.endsWith(".json")) {
            String restoredPropertiesString = ExportImportUtils.getFileContentByName(chainFilesDir,
                    propertiesFilename);
            if (StringUtils.isNotEmpty(restoredPropertiesString)) {
                @SuppressWarnings("unchecked")
                HashMap<String, Object> restoredProperties =
                        objectMapper.readValue(restoredPropertiesString, HashMap.class);

                if (restoredProperties != null) {
                    if (!checkAndPutScriptContentInServiceCall(element, restoredProperties, propertiesFilename)) {
                        element.getProperties().putAll(restoredProperties);
                    }
                }
            } else {
                throw new IOException("Could not find file with properties: " + propertiesFilename);
            }

        } else if (ExportImportUtils.isPropertiesFileGroove(element.getProperties())
                || ExportImportUtils.isPropertiesFileSql(element.getProperties())
                || propertiesFilename.endsWith(".groovy")) {
            String stringValue = ExportImportUtils.getFileContentByName(chainFilesDir,
                    propertiesFilename);

            if (stringValue != null) {
                if (!checkAndPutScriptContentInServiceCall(element, stringValue, propertiesFilename)) {
                    element.getProperties().put(
                            (String) element.getProperties().get(PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY),
                            stringValue);
                }
            } else {
                throw new IOException("Could not find file with properties: " + propertiesFilename);
            }
        } else {
            throw new IllegalArgumentException(
                    "Invalid property '" + EXPORT_FILE_EXTENSION_PROPERTY
                            + "' of element " + element.getId());
        }
    }

    private boolean checkAndPutScriptContentInServiceCall(ChainElement element, Object stringValue, String propertiesFilename) {
        if (element.getType().equals(SERVICE_CALL)) {
            List<Map<String, Object>> afterPropertyList = (List<Map<String, Object>>) element.getProperties().get(AFTER);
            if (!CollectionUtils.isEmpty(afterPropertyList)) {
                for (Map<String, Object> afterProp : afterPropertyList) {
                    if (SCRIPT.equals(afterProp.get(TYPE)) && propertiesFilename.equals(afterProp.get(FILE_NAME_PROPERTY))) {
                        afterProp.put(SCRIPT, stringValue);
                        break;
                    } else if (null != afterProp.get(TYPE) && propertiesFilename.equals(afterProp.get(FILE_NAME_PROPERTY))) {
                        updateMapperProperty(afterProp, stringValue);
                        break;
                    }
                }
            }
            Map beforePropMap = (Map) element.getProperties().get(BEFORE);
            if (!CollectionUtils.isEmpty(beforePropMap)) {
                if (SCRIPT.equals(beforePropMap.get(TYPE)) && propertiesFilename.equals(beforePropMap.get(FILE_NAME_PROPERTY))) {
                    beforePropMap.put(SCRIPT, stringValue);
                } else if (null != beforePropMap.get(TYPE) && propertiesFilename.equals(beforePropMap.get(FILE_NAME_PROPERTY))) {
                    updateMapperProperty(beforePropMap, stringValue);
                }
            }
            return true;
        }
        return false;
    }

    private void updateMapperProperty(Map<String, Object> property, Object stringValue) {
        HashMap<String, Object> map = (HashMap<String, Object>) stringValue;
        if (MAPPER.equals(property.get(TYPE))) {
            property.put(MAPPING, map.get(MAPPING));
            property.put(SOURCE, map.get(SOURCE));
            property.put(TARGET, map.get(TARGET));
        } else {
            property.put(MAPPING_DESCRIPTION, map.get(MAPPING_DESCRIPTION));
        }
    }

    protected File getChainYAMLFile(File chainDir) throws IOException {
        if (chainDir.listFiles() != null) {
            List<File> dirFiles = Arrays.asList(Objects.requireNonNull(chainDir.listFiles()));
            return dirFiles.stream().filter(
                            f -> f.getName().startsWith(CHAIN_YAML_NAME_PREFIX)
                                    && f.getName().endsWith(YAML_FILE_NAME_POSTFIX))
                    .findFirst().orElseThrow(() -> new RuntimeException(
                            "Directory " + chainDir.getName() + " does not contain chain YAML file")
                    );
        } else {
            throw new RuntimeException("Directory " + chainDir.getName() + " is empty");
        }
    }

    private String getFileContent(MultipartFile file) throws IOException {
        return new String(file.getBytes());
    }

    private String generateImportFolderName() {
        return UUID.randomUUID().toString();
    }

    protected void deployChains(List<ChainDeployPrepare> chainsToDeploy) {
        deployChains(chainsToDeploy, null);
    }

    protected void deployChains(List<ChainDeployPrepare> chainsToDeploy, String importId) {
        List<Deployment> oldDeploysList = new ArrayList<>();
        int total = chainsToDeploy.size();
        int counter = 0;

        for (ChainDeployPrepare entity : chainsToDeploy) {
            String chainId = entity.getImportChainResult().getId();
            oldDeploysList.addAll(deploymentService.findAllByChainId(chainId));
        }
        for (ChainDeployPrepare entity : chainsToDeploy) {
            calculateDeployAsyncStatus(importId, total, counter);
            counter++;

            ImportChainResult importChainDTO = entity.getImportChainResult();
            Snapshot snapshot = entity.getSnapshot();
            List<DeploymentExternalEntity> deployments = importChainDTO.getDeployments();

            if (!CollectionUtils.isEmpty(deployments)) {
                try {
                    for (DeploymentExternalEntity deployment : deployments) {
                        if (engineService.isDevMode()
                                || engineService.getDomainByName(deployment.getDomain()) != null) {
                            createDeployment(snapshot, oldDeploysList, deployment);
                        } else {
                            importChainDTO.setStatus(ImportEntityStatus.ERROR);
                            importChainDTO.setErrorMessage(
                                    SAVED_WITHOUT_DEPLOYMENT_ERROR_MESSAGE + "domain "
                                            + deployment.getDomain() + " doesn't exists");
                        }
                    }
                } catch (Exception e) {
                    log.error("Unable to deploy chain {} {}", importChainDTO.getId(),
                            e.getMessage());
                    importChainDTO.setStatus(ImportEntityStatus.ERROR);
                    importChainDTO.setErrorMessage(
                            SAVED_WITHOUT_DEPLOYMENT_ERROR_MESSAGE + e.getMessage());
                }
            }
        }
    }

    protected Deployment createDeployment(Snapshot snapshot, List<Deployment> excludeDeployments,
                                          DeploymentExternalEntity deployment) {
        Deployment deploymentConfig = new Deployment();
        deploymentConfig.setDomain(deployment.getDomain());
        return deploymentService.create(
                deploymentConfig,
                snapshot.getChain(),
                snapshot,
                excludeDeployments
        );
    }

    protected ChainCommitDTO importToCommitDTOTransform(ImportChainResult importChainDTO, String archiveName) {
        ChainCommitDTO chainCommitDTO = ChainCommitDTO.builder()
                .id(importChainDTO.getId())
                .name(importChainDTO.getName())
                .archiveName(archiveName)
                .message(importChainDTO.getErrorMessage())
                .build();

        ChainCommitAction action;
        switch (importChainDTO.getStatus()) {
            case CREATED:
                action = ChainCommitAction.CREATED;
                break;
            case UPDATED:
                action = ChainCommitAction.UPDATED;
                break;
            case ERROR:
                action = ChainCommitAction.ERROR;
                break;
            default:
                action = ChainCommitAction.ERROR;
                chainCommitDTO.setMessage("Unknown import status: " + importChainDTO.getStatus());
                break;
        }
        chainCommitDTO.setStatus(action);

        return chainCommitDTO;
    }

    public File unpackZIP(InputStream is) throws IOException {
        return ExportImportUtils.extractDirectoriesFromZip(is, generateImportFolderName());
    }

    private ImportDTO restoreChainsFromDir(File importDirectory, List<ChainCommitRequest> commitRequests, String importId, Set<String> technicalLabels) {
        List<File> chainFilesDirectories = null;
        ImportDTO response = new ImportDTO();

        importDirectory = new File(importDirectory + File.separator + CHAINS_ARCH_PARENT_DIR);
        File[] listFiles = importDirectory.listFiles();
        if (listFiles != null) {
            chainFilesDirectories = Arrays.stream(Objects.requireNonNull(listFiles))
                    .filter(File::isDirectory)
                    .collect(Collectors.toList());
        }
        if (!CollectionUtils.isEmpty(chainFilesDirectories)) {
            int total = chainFilesDirectories.size();
            int counter = 0;
            for (File chainFilesDir : chainFilesDirectories) {
                calculateImportAsyncStatus(importId, total, counter);
                counter++;

                ImportChainResult chainDTO = restoreChainFromDir(chainFilesDir, commitRequests, technicalLabels);
                if (chainDTO != null) {
                    response.getChains().add(chainDTO);
                }
            }
        }

        return response;
    }

    private void calculateImportAsyncStatus(String importId, int total, int counter) {
        importProgressService.calculateImportStatus(importId, total, counter, 0, ASYNC_IMPORT_PERCENTAGE_THRESHOLD);
    }

    private void calculateSnapshotAsyncStatus(String importId, int total, int counter) {
        importProgressService.calculateImportStatus(importId, total, counter, ASYNC_IMPORT_PERCENTAGE_THRESHOLD, ASYNC_SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD);
    }

    private void calculateDeployAsyncStatus(String importId, int total, int counter) {
        importProgressService.calculateImportStatus(importId, total, counter, ASYNC_SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD, 100);
    }

    public ImportPreviewDTO importZIPAsPreview(InputStream is) {
        File importDirectory = null;
        ImportPreviewDTO response = new ImportPreviewDTO();
        List<File> chainFilesDirectories = null;

        try {
            importDirectory = new File(
                    ExportImportUtils.extractDirectoriesFromZip(is, generateImportFolderName())
                            + File.separator + CHAINS_ARCH_PARENT_DIR);
            File[] listFiles = importDirectory.listFiles();
            importDirectory = importDirectory.getParentFile();
            if (listFiles != null) {
                chainFilesDirectories = Arrays.stream(Objects.requireNonNull(listFiles))
                        .filter(File::isDirectory)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Exception while importing preview from zip: ", e);
            response.setErrorMessage(
                    "Exception while importing preview from zip: " + e.getMessage());
            ExportImportUtils.deleteFile(importDirectory);
            return response;
        }

        if (!CollectionUtils.isEmpty(chainFilesDirectories)) {
            for (File chainFilesDir : chainFilesDirectories) {
                response.getChains().add(restoreChainPreviewFromDir(chainFilesDir));
            }
        }

        ExportImportUtils.deleteFile(importDirectory);

        return response;
    }

    protected String migrateToActualFileVersion(String fileContent) throws Exception {
        return chainFileMigrationService.migrateToActualVersion(fileContent);
    }

    protected ChainCompareDTO getYamlBasicChainInfo(String yamlContent) throws IOException {
        ChainCommitRequestAction deployAction;
        JsonNode node = this.yamlMapper.readTree(yamlContent);

        String chainId = node.get("id") != null ? node.get("id").asText(null) : null;
        if (chainId == null) {
            throw new RuntimeException("Missing id field in chain file");
        }
        String chainName = node.get("name") != null ? node.get("name").asText("") : "";
        JsonNode deployActionNode = node.get("deployAction");

        if (deployActionNode == null || deployActionNode.isNull()) {
            deployAction = ChainCommitRequestAction.SNAPSHOT;
        } else {
            deployAction = ChainCommitRequestAction.valueOf(deployActionNode.asText());
        }

        long modified = node.has("modifiedWhen") ? node.get("modifiedWhen").asLong() : 0;

        return ChainCompareDTO.builder()
                .id(chainId)
                .name(chainName)
                .deployAction(deployAction)
                .modified(modified)
                .build();
    }
}
