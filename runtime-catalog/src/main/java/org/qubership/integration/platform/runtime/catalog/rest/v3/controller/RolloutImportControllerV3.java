package org.qubership.integration.platform.runtime.catalog.rest.v3.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportSnapshotClientResponse;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.RolloutImportService;
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
@RequestMapping("/v3/rollout-import")
@Tag(name = "rollout-import", description = "Rollout import")
public class RolloutImportControllerV3 {

    private final RolloutImportService rolloutImportService;

    public RolloutImportControllerV3(RolloutImportService rolloutImportService) {
        this.rolloutImportService = rolloutImportService;
    }

    @PutMapping(value = "/{snapshotId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rollout package to catalog")
    public ResponseEntity<RolloutImportSnapshotClientResponse> receiveQcpSnapshot(
            @RequestBody RolloutImportConfigurationRequest request,
            @PathVariable @Parameter(description = "Rollout snapshot") String snapshotId,
            @RequestHeader(value = RolloutImportConstants.CALLBACK_URL_HEADER, required = false) String callbackUrl
    ) {
        log.info("Rollout snapshot id={} is received", snapshotId);

        rolloutImportService.processAsync(snapshotId, request, callbackUrl);

        RolloutImportSnapshotClientResponse accepted = RolloutImportSnapshotClientResponse.builder()
                .status(RolloutImportConstants.STATUS_ROLLOUT_IN_PROGRESS)
                .build();
        log.info("Responding HTTP 202 with status={} for snapshot={}", RolloutImportConstants.STATUS_ROLLOUT_IN_PROGRESS, snapshotId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }
}
