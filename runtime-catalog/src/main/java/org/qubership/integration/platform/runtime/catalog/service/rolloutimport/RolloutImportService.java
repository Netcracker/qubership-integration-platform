package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.RolloutImportException;
import org.qubership.integration.platform.runtime.catalog.model.ErrorCodePayload;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportSnapshotClientResponse;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.exception.ErrorCode.*;
import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.STATUS_ROLLOUT_FAILED;
import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.STATUS_ROLLOUT_SUCCESS;

@Slf4j
@Service
public class RolloutImportService {

    private static final long IMPORT_SESSION_POLL_INTERVAL_MS = 2000L;

    private final RolloutImportSnapshotToImportDirectoryService snapshotToImportDirectoryService;
    private final GeneralImportService generalImportService;
    private final RolloutImportCallbackClient rolloutImportCallbackClient;
    @Value("${spring.application.name}")
    private String clientId;
    @Value("${kubernetes.cluster.namespace}")
    private String namespace;

    public RolloutImportService(
            RolloutImportSnapshotToImportDirectoryService snapshotToImportDirectoryService,
            GeneralImportService generalImportService,
            RolloutImportCallbackClient rolloutImportCallbackClient
    ) {
        this.snapshotToImportDirectoryService = snapshotToImportDirectoryService;
        this.generalImportService = generalImportService;
        this.rolloutImportCallbackClient = rolloutImportCallbackClient;
    }

    @Async
    public void processAsync(String snapshotId, RolloutImportConfigurationRequest request, String callbackUrl) {
        File importDirectory = null;
        boolean importStarted = false;
        String importId = null;

        log.info("Async rollout started: snapshotId={}", snapshotId);

        try {
            ImportConfig importConfig = snapshotToImportDirectoryService.toImportConfig(request, snapshotId);

            if (importConfig.isEmpty()) {
                log.warn("Package is empty after parsing — no importable configurations for snapshotId={}", snapshotId);
                throw new RolloutImportException(INVALID_ROLLOUT_SNAPSHOT_ERROR);
            }

            importDirectory = snapshotToImportDirectoryService.writeImportDirectory(importConfig);

            importId = generalImportService.importDirectoryAsync(
                    importDirectory,
                    new ImportRequest(),
                    Collections.emptySet(),
                    false
            );
            importStarted = true;

            waitForImportCompletion(snapshotId, importId);

            rolloutImportCallbackClient.sendCallback(snapshotId, callbackUrl, buildClientResponse(STATUS_ROLLOUT_SUCCESS, null));
        } catch (Exception exception) {
            log.error(
                    "Rollout import FAILED for snapshotId={}:",
                    snapshotId,
                    exception
            );
            List<ErrorCodePayload> errors = resolveErrors(exception);
            log.info(
                    "Sending FAILED callback: code={} message={}",
                    errors.getFirst().getCode(),
                    errors.getFirst().getMessage()
            );
            rolloutImportCallbackClient.sendCallback(snapshotId, callbackUrl, buildClientResponse(STATUS_ROLLOUT_FAILED, errors));
        } finally {
            if (!importStarted) {
                log.info("Import was not started — cleaning temp directory {}", importDirectory);
                ExportImportUtils.deleteFile(importDirectory);
            } else {
                log.debug("Temp import directory cleanup delegated to GeneralImportService (importId={})", importId);
            }
        }
    }

    private void waitForImportCompletion(String snapshotId, String importId) {
        ImportSession importSession = awaitImportSessionWithResult(importId);
        ImportResult importResult = importSession.getResult();
        if (!importResult.hasErrors()) {
            return;
        }

        log.error(
                "Catalog import failed for snapshotId={}, importId={}",
                snapshotId,
                importId
        );
        List<ErrorCodePayload> errors = mapImportResultErrors(importResult);
        throw new RolloutImportException(errors);
    }

    private List<ErrorCodePayload> mapImportResultErrors(ImportResult importResult) {
        List<ErrorCodePayload> errors = new ArrayList<>();
        for (ImportChainResult importChainResult : importResult.getChains()) {
            if (ImportEntityStatus.ERROR.equals(importChainResult.getStatus())) {
                errors.add(IMPORT_FAILED_ERROR.toPayload("Error occurred in chain with id=" + importChainResult.getId()
                    + ", name=" + importChainResult.getName()
                    + ", error=" + importChainResult.getErrorMessage()
                ));
            }
        }
        for (ImportSystemResult importSystemResult : importResult.getSystems()) {
            if (ImportSystemStatus.ERROR.equals(importSystemResult.getStatus())) {
                errors.add(IMPORT_FAILED_ERROR.toPayload("Error occurred in service with id=" + importSystemResult.getId()
                    + ", name=" + importSystemResult.getName()
                    + ", error=" + importSystemResult.getMessage()
                ));
            }
        }
        for (ImportSystemResult importSystemResult : importResult.getContextService()) {
            if (ImportSystemStatus.ERROR.equals(importSystemResult.getStatus())) {
                errors.add(IMPORT_FAILED_ERROR.toPayload("Error occurred in context service with id=" + importSystemResult.getId()
                    + ", name=" + importSystemResult.getName()
                    + ", error=" + importSystemResult.getMessage()
                ));
            }
        }
        if (errors.isEmpty()) {
            errors.add(IMPORT_FAILED_ERROR.toPayload());
        }
        return errors;
    }

    private ImportSession awaitImportSessionWithResult(String importId) {
        while (true) {
            ImportSession importSession = generalImportService.getImportSession(importId);
            if (importSession == null) {
                throw new IllegalStateException("Import session not found: " + importId);
            }

            if (importSession.isDone()) {
                return importSession;
            }

            try {
                Thread.sleep(IMPORT_SESSION_POLL_INTERVAL_MS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new RolloutImportException(IMPORT_FAILED_ERROR);
            }
        }
    }

    private RolloutImportSnapshotClientResponse buildClientResponse(String status, List<ErrorCodePayload> errors) {
        RolloutImportSnapshotClientResponse.RolloutImportSnapshotClientResponseBuilder builder = RolloutImportSnapshotClientResponse.builder()
                .clientId(clientId)
                .namespace(namespace)
                .status(status);
        if (STATUS_ROLLOUT_FAILED.equals(status) && errors != null) {
            builder.errors(errors);
        }
        return builder.build();
    }

    private List<ErrorCodePayload> resolveErrors(Exception exception) {
        if (exception instanceof RolloutImportException rolloutException) {
            return List.copyOf(rolloutException.getErrors());
        }
        return List.of(UNEXPECTED_ROLLOUT_IMPORT_ERROR.toPayload(exception.getMessage()));
    }
}
