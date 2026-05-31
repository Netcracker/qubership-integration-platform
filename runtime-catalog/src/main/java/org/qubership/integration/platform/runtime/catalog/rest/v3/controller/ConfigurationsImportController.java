package org.qubership.integration.platform.runtime.catalog.rest.v3.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.constant.QcpConstants;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpSnapshotClientResponse;
import org.qubership.integration.platform.runtime.catalog.service.qcp.QcpRolloutService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/v3/${qip.qcp.name}/import")
@Tag(name = "qcp-import", description = "QCP configuration rollout")
public class ConfigurationsImportController {

    private final QcpRolloutService qcpRolloutService;

    public ConfigurationsImportController(QcpRolloutService qcpRolloutService) {
        this.qcpRolloutService = qcpRolloutService;
    }

    @PutMapping(value = "/{snapshotId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rollout QCP package to catalog via QCP push method")
    public ResponseEntity<QcpSnapshotClientResponse> receiveQcpSnapshot(
            @RequestBody QcpImportConfigurationRequest request,
            @PathVariable @Parameter(description = "Effective snapshot identifier") String snapshotId,
            @RequestHeader(value = QcpConstants.CALLBACK_URL_HEADER, required = false) String callbackUrl
    ) {
        log.info("Effective snapshot id={} is received", snapshotId);

        qcpRolloutService.processAsync(snapshotId, request, callbackUrl);

        QcpSnapshotClientResponse accepted = QcpSnapshotClientResponse.builder()
                .status(QcpConstants.STATUS_ROLLOUT_IN_PROGRESS)
                .build();
        log.info("Responding HTTP 202 with status={} for snapshot={}", QcpConstants.STATUS_ROLLOUT_IN_PROGRESS, snapshotId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }
}
