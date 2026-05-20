package org.qubership.integration.platform.runtime.catalog.rest.v2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v2/catalog/snapshots", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
public class SnapshotControllerV2 {
    private final SnapshotService snapshotService;

    @Autowired
    public SnapshotControllerV2(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping("/bulk-delete")
    @Operation(description = "Bulk delete snapshots")
    public ResponseEntity<Void> bulkDeleteSnapshots(@RequestBody @Parameter(description = "List of snapshots' IDs") List<String> snapshotIds) {
        log.info("Request to delete snapshots: {}", snapshotIds);
        snapshotIds.forEach(snapshotId -> {
            try {
                snapshotService.deleteById(snapshotId);
            } catch (EntityNotFoundException e) {
                log.warn("Snapshot not found: {}", snapshotId);
            }
        });
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
