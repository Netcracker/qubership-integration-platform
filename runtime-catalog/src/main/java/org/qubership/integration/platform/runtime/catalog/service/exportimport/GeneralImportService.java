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

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.context.RequestIdContext;
import org.qubership.integration.platform.catalog.mapping.exportimport.instructions.GeneralInstructionsMapper;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.GeneralImportInstructionsConfig;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.difference.ChainDifferenceRequest;
import org.qubership.integration.platform.catalog.service.difference.EntityDifferenceResult;
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.variable.ImportVariablesResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportPreviewResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
@Service
public class GeneralImportService {

    private final CommonVariablesImportService commonVariablesImportService;
    private final SystemExportImportService systemExportImportService;
    private final ChainImportService chainImportService;
    private final ImportSessionService importSessionService;
    private final ActionsLogService actionsLogService;
    private final ImportInstructionsService importInstructionsService;
    private final GeneralInstructionsMapper generalInstructionsMapper;

    @Autowired
    public GeneralImportService(
            CommonVariablesImportService commonVariablesImportService,
            SystemExportImportService systemExportImportService,
            ChainImportService chainImportService,
            ImportSessionService importSessionService,
            ActionsLogService actionsLogService,
            ImportInstructionsService importInstructionsService,
            GeneralInstructionsMapper generalInstructionsMapper
    ) {
        this.commonVariablesImportService = commonVariablesImportService;
        this.systemExportImportService = systemExportImportService;
        this.chainImportService = chainImportService;
        this.importSessionService = importSessionService;
        this.actionsLogService = actionsLogService;
        this.importInstructionsService = importInstructionsService;
        this.generalInstructionsMapper = generalInstructionsMapper;
    }

    @Nullable
    public ImportSession getImportSession(String importId) {
        return importSessionService.getImportSession(importId);
    }

    public ImportPreviewResponse getImportPreview(MultipartFile file) {
        File unpackedDirectory = null;

        try {
            unpackedDirectory = unpackDirectory(file);

            List<ImportInstruction> importInstructions;
            File importInstructionsConfigFile = new File(unpackedDirectory, importInstructionsService.getInstructionsFileName());
            if (importInstructionsConfigFile.exists()) {
                importInstructions = importInstructionsService.getImportInstructionsForPreview(importInstructionsConfigFile);
            } else {
                importInstructions = importInstructionsService.getAllImportInstructions();
            }

            GeneralImportInstructionsConfig instructionsConfig = generalInstructionsMapper.asConfig(importInstructions);
            return ImportPreviewResponse.builder()
                    .variables(commonVariablesImportService.getCommonVariablesImportPreview(unpackedDirectory))
                    .chains(chainImportService.getChainsImportPreview(unpackedDirectory, instructionsConfig.getChains()))
                    .systems(systemExportImportService.getSystemsImportPreview(unpackedDirectory, instructionsConfig.getServices()))
                    .instructions(generalInstructionsMapper.asDTO(importInstructions))
                    .build();
        } finally {
            ExportImportUtils.deleteFile(unpackedDirectory);
        }
    }

    public EntityDifferenceResult compareImportEntities(MultipartFile file, ChainDifferenceRequest diffRequest) {
        File unpackedDirectory = null;

        try {
            unpackedDirectory = unpackDirectory(file);
            return chainImportService.compareChains(unpackedDirectory, diffRequest);
        } finally {
            ExportImportUtils.deleteFile(unpackedDirectory);
        }
    }

    public String importFileAsync(MultipartFile file, ImportRequest importRequest, Set<String> technicalLabels , boolean validateByHash) {
        String importId = UUID.randomUUID().toString();

        importSessionService.deleteObsoleteImportSessionStatuses();
        importSessionService.setImportProgressPercentage(importId, 0);

        File unpackedDirectory = unpackDirectory(file);
        logImportAction(file.getOriginalFilename());

        String requestId = RequestIdContext.get();
        CompletableFuture.supplyAsync(() -> {
            RequestIdContext.set(requestId);

            log.info("Import session {} started", importId);

            ArrayList<ImportInstructionResult> importInstructionResults = new ArrayList<>();

            File importInstructionsConfigFile = new File(unpackedDirectory, importInstructionsService.getInstructionsFileName());
            if (importInstructionsConfigFile.exists()) {
                log.info("Start uploading import instructions");

                importInstructionResults.addAll(
                        importInstructionsService.uploadImportInstructionsConfig(importInstructionsConfigFile, technicalLabels)
                );
            }

            ImportVariablesResult variablesResult = commonVariablesImportService
                    .importCommonVariables(unpackedDirectory, importRequest.getVariablesCommitRequest(), importId);
            ImportSystemsAndInstructionsResult importSystemsAndInstructionsResult = systemExportImportService
                    .importSystems(unpackedDirectory, importRequest.getSystemsCommitRequest(), importId, technicalLabels);
            ImportChainsAndInstructionsResult importChainsAndInstructionsResult = chainImportService
                    .importChains(unpackedDirectory, importRequest.getChainCommitRequests(), importId, technicalLabels, validateByHash);

            importInstructionResults.addAll(importChainsAndInstructionsResult.instructionResults());
            importInstructionResults.addAll(importSystemsAndInstructionsResult.instructionResults());
            importInstructionResults.addAll(variablesResult.getInstructions());
            return ImportResult.builder()
                    .chains(importChainsAndInstructionsResult.chainResults())
                    .systems(importSystemsAndInstructionsResult.importSystemResults())
                    .variables(variablesResult.getVariables())
                    .instructionsResult(importInstructionResults)
                    .build();
        }).whenCompleteAsync((response, throwable) -> {
            RequestIdContext.set(requestId);

            completeAsyncImport(importId, response, unpackedDirectory, throwable);
        });
        return importId;
    }

    private void completeAsyncImport(String importId, ImportResult importResult, File unpackedDirectory, Throwable throwable) {
        ExportImportUtils.deleteFile(unpackedDirectory);
        ImportSession importSession = new ImportSession();
        importSession.setId(importId);
        importSession.setCompletion(100);
        if (importResult != null) {
            importSession.setResult(importResult);
        }
        if (throwable != null) {
            String errorMessage = throwable instanceof CompletionException exception
                    ? exception.getCause().getMessage()
                    : throwable.getMessage();
            importSession.setError(errorMessage);
            log.error("Error async importing file", throwable);
        }

        importSessionService.saveImportSession(importSession);
    }

    private File unpackDirectory(MultipartFile file) {
        File unpackDirectory = null;
        try (InputStream is = file.getInputStream()) {
            unpackDirectory = unpackZIP(is);
        } catch (Exception e) {
            ExportImportUtils.deleteFile(unpackDirectory);
            log.warn("Exception while extract files from zip", e);
            throw new RuntimeException("Exception while extract files from zip", e);
        }

        return unpackDirectory;
    }

    private File unpackZIP(InputStream is) throws IOException {
        return ExportImportUtils.extractDirectoriesFromZip(is, UUID.randomUUID().toString());
    }

    private void logImportAction(String archiveName) {
        actionsLogService.logAction(ActionLog.builder()
                .entityType(EntityType.CHAINS)
                .entityName(archiveName)
                .operation(LogOperation.IMPORT)
                .build());
    }
}
