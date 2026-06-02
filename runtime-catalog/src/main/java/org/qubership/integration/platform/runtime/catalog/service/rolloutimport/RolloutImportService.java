package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.RolloutImportException;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportClientError;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportSnapshotClientResponse;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

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
                throw new RolloutImportException(
                        clientError("QIP-0000", "Empty configuration package"),
                        "Rollout package does not contain importable configurations"
                );
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
                    "QCP rollout FAILED for snapshotId={}:",
                    snapshotId,
                    exception
            );
            RolloutImportClientError error = resolveError(exception);
            log.info(
                    "Sending FAILED callback: code={} message={}",
                    error.getCode(),
                    error.getMessage()
            );
            rolloutImportCallbackClient.sendCallback(snapshotId, callbackUrl, buildClientResponse(STATUS_ROLLOUT_FAILED, error));
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
        throw new RolloutImportException(
                clientError("QIP-0000", "Import failed"),
                "Import failed"
        );
    }

    private ImportSession awaitImportSessionWithResult(String importId) {
        while (true) {
            ImportSession importSession = generalImportService.getImportSession(importId);
            if (importSession == null) {
                throw new IllegalStateException("Import session not found: " + importId);
            }

            if (importSession.isDone() && importSession.getResult() != null) {
                return importSession;
            }

            if (importSession.isDone() && importSession.getError() != null) {
                throw new RolloutImportException(
                        clientError("QIP-0000", importSession.getError()),
                        "Import failed"
                );
            }

            try {
                Thread.sleep(IMPORT_SESSION_POLL_INTERVAL_MS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new RolloutImportException(
                        clientError("QIP-0000", "Import session wait interrupted"),
                        "Import session wait interrupted"
                );
            }
        }
    }

    private RolloutImportSnapshotClientResponse buildClientResponse(String status, RolloutImportClientError error) {
        RolloutImportSnapshotClientResponse.RolloutImportSnapshotClientResponseBuilder builder = RolloutImportSnapshotClientResponse.builder()
                .clientId(clientId)
                .namespace(namespace)
                .status(status);
        if (STATUS_ROLLOUT_FAILED.equals(status) && error != null) {
            builder.errors(List.of(error));
        }
        return builder.build();
    }

    private RolloutImportClientError resolveError(Exception exception) {
        if (exception instanceof RolloutImportException rolloutException && rolloutException.getError() != null) {
            return rolloutException.getError();
        }
        if (exception instanceof IOException) {
            return clientError("QIP-0000", "Failed to prepare import directory: " + exception.getMessage());
        }
        return clientError("QIP-0000", exception.getMessage());
    }

    private static RolloutImportClientError clientError(String code, String message) {
        return RolloutImportClientError.builder()
                .code(code)
                .reason(code)
                .message(message)
                .build();
    }
}
