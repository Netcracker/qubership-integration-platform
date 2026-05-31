package org.qubership.integration.platform.runtime.catalog.service.qcp;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.QcpProperties;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.QcpRolloutException;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpClientError;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpSnapshotClientResponse;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.model.constant.QcpConstants.ERROR_CODE_EMPTY_PACKAGE;
import static org.qubership.integration.platform.runtime.catalog.model.constant.QcpConstants.ERROR_CODE_IMPORT_FAILED;
import static org.qubership.integration.platform.runtime.catalog.model.constant.QcpConstants.ERROR_CODE_INTERNAL;
import static org.qubership.integration.platform.runtime.catalog.model.constant.QcpConstants.STATUS_ROLLOUT_FAILED;
import static org.qubership.integration.platform.runtime.catalog.model.constant.QcpConstants.STATUS_ROLLOUT_SUCCESS;

@Slf4j
@Service
public class QcpRolloutService {

    private final QcpProperties qcpProperties;
    private final QcpSnapshotToImportDirectoryService snapshotToImportDirectoryService;
    private final GeneralImportService generalImportService;
    private final QcpCallbackClient qcpCallbackClient;

    public QcpRolloutService(
            QcpProperties qcpProperties,
            QcpSnapshotToImportDirectoryService snapshotToImportDirectoryService,
            GeneralImportService generalImportService,
            QcpCallbackClient qcpCallbackClient
    ) {
        this.qcpProperties = qcpProperties;
        this.snapshotToImportDirectoryService = snapshotToImportDirectoryService;
        this.generalImportService = generalImportService;
        this.qcpCallbackClient = qcpCallbackClient;
    }

    @Async
    public void processAsync(String snapshotId, QcpImportConfigurationRequest request, String callbackUrl) {
        File importDirectory = null;
        boolean importStarted = false;
        String importId = null;

        log.info("Async rollout started: snapshotId={}", snapshotId);

        try {
            ImportConfig importConfig = snapshotToImportDirectoryService.toImportConfig(request, snapshotId);

            if (importConfig.isEmpty()) {
                log.warn("Package is empty after parsing — no importable configurations for snapshotId={}", snapshotId);
                throw new QcpRolloutException(
                        clientError(ERROR_CODE_EMPTY_PACKAGE, "Empty configuration package"),
                        "QCP package does not contain importable configurations"
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

            qcpCallbackClient.sendCallback(snapshotId, callbackUrl, buildClientResponse(STATUS_ROLLOUT_SUCCESS, null));
        } catch (Exception exception) {
            log.error(
                    "QCP rollout FAILED for snapshotId={}:",
                    snapshotId,
                    exception
            );
            QcpClientError error = resolveError(exception);
            log.info(
                    "Sending FAILED callback: code={} message={}",
                    error.getCode(),
                    error.getMessage()
            );
            qcpCallbackClient.sendCallback(snapshotId, callbackUrl, buildClientResponse(STATUS_ROLLOUT_FAILED, error));
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
        generalImportService.awaitImportCompletion(importId);

        ImportSession importSession = generalImportService.getImportSession(importId);
        if (importSession == null) {
            throw new QcpRolloutException(
                    clientError(ERROR_CODE_INTERNAL, "Import session missing after completion"),
                    "Import session not found: " + importId
            );
        }
        if (importSession.getError() != null && !importSession.getError().isBlank()) {
            log.error("Import session error payload: {}", importSession.getError());
            throw new QcpRolloutException(
                    clientError(ERROR_CODE_IMPORT_FAILED, "Catalog import failed"),
                    importSession.getError()
            );
        }
        if (importSession.getResult() != null) {
            log.info(
                    "Import result summary: chains={} systems={} variables={} for snapshotId={}",
                    importSession.getResult().getChains() != null ? importSession.getResult().getChains().size() : 0,
                    importSession.getResult().getSystems() != null ? importSession.getResult().getSystems().size() : 0,
                    importSession.getResult().getVariables() != null ? importSession.getResult().getVariables().size() : 0,
                    snapshotId
            );
        }
    }

    private QcpSnapshotClientResponse buildClientResponse(String status, QcpClientError error) {
        QcpSnapshotClientResponse.QcpSnapshotClientResponseBuilder builder = QcpSnapshotClientResponse.builder()
                .clientId(qcpProperties.getClientId())
                .namespace(qcpProperties.getNamespace())
                .status(status);
        if (STATUS_ROLLOUT_FAILED.equals(status) && error != null) {
            builder.errors(List.of(error));
        }
        return builder.build();
    }

    private QcpClientError resolveError(Exception exception) {
        if (exception instanceof QcpRolloutException rolloutException && rolloutException.getError() != null) {
            return rolloutException.getError();
        }
        if (exception instanceof IOException) {
            return clientError(ERROR_CODE_INTERNAL, "Failed to prepare import directory: " + exception.getMessage());
        }
        return clientError(ERROR_CODE_INTERNAL, exception.getMessage());
    }

    private static QcpClientError clientError(String code, String message) {
        return QcpClientError.builder()
                .code(code)
                .reason(code)
                .message(message)
                .build();
    }
}
