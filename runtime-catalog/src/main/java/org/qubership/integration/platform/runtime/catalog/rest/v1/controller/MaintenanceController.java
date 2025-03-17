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

package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/maintenance", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "maintenance-controller", description = "Maintenance Controller")
public class MaintenanceController {

    private final SnapshotService snapshotService;

    public MaintenanceController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
    description = "Delete old snapshots from all chains")
    @PostMapping("/snapshots/prune")
    public ResponseEntity<Void> pruneSnapshots(@RequestParam @Valid @Min(0) @Parameter(description = "Snapshots older than that amount of days will be deleted") int olderThanDays,
                                               @RequestParam(defaultValue = "1000") @Valid @Min(1) @Parameter(description = "How much entries will be deleted at the same time") int chunk) {
        log.info("Request to clear snapshots older than {} day(s) by {} snapshots", olderThanDays, chunk);
        snapshotService.pruneSnapshotsAsync(olderThanDays, chunk);
        return ResponseEntity.accepted().build();
    }
}
