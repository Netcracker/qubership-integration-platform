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
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.snapshot.SnapshotRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.snapshot.SnapshotResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.SnapshotMapper;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/chains/{chainId}/snapshots", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "snapshot-controller", description = "Snapshot Controller")
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final SnapshotMapper snapshotMapper;

    @Autowired
    public SnapshotController(SnapshotService snapshotService,
                              SnapshotMapper snapshotMapper) {
        this.snapshotService = snapshotService;
        this.snapshotMapper = snapshotMapper;
    }

    @GetMapping
    @Operation(description = "Find all snapshots by specified chain without xml definition")
    public ResponseEntity<List<SnapshotResponse>> findByChainIdLight(@PathVariable @Parameter(description = "Chain id") String chainId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find all snapshots for chain: {}", chainId);
        }
        var entities = snapshotService.findByChainIdLight(chainId);
        var responseList = snapshotMapper.asResponse(entities);
        responseList.forEach(snapshotDto -> snapshotDto.setXmlDefinition(null));
        return ResponseEntity.ok(responseList);
    }

    @GetMapping("/{snapshotId}")
    @Operation(description = "Get particular snapshot")
    public ResponseEntity<SnapshotResponse> findById(
            @PathVariable @Parameter(description = "Chain id") String chainId,
            @PathVariable @Parameter(description = "Snapshot id") String snapshotId,
            @RequestParam(required = false, defaultValue = "false") @Parameter(description = "Whether response should not include xml definition of the chain") boolean light
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find snapshot {} in chain {}: ", snapshotId, chainId);
        }
        var entity = snapshotService.findById(snapshotId);
        var response = snapshotMapper.asResponse(entity);
        if (light) {
            response.setXmlDefinition(null);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(description = "Generate snapshot on current chain state")
    public ResponseEntity<SnapshotResponse> build(@PathVariable @Parameter(description = "Chain id") String chainId) {
        log.info("Request to build snapshot for chain with id: {}", chainId);
        var entity = snapshotService.build(chainId);
        var response = snapshotMapper.asResponse(entity);
        response.setXmlDefinition(null);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{snapshotId}")
    @Operation(description = "Update information for particular snapshot")
    public ResponseEntity<SnapshotResponse> updateSnapshot(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                           @PathVariable @Parameter(description = "Snapshot id") String snapshotId,
                                                           @RequestBody @Parameter(description = "Snapshot update request object") SnapshotRequest request) {
        log.info("Request to build snapshot for chain with id: {}", chainId);
        Snapshot snapshot = snapshotService.merge(chainId, snapshotId, snapshotMapper.asRequest(request));
        SnapshotResponse response = snapshotMapper.asResponse(snapshot);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/{snapshotId}/revert", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Revert chain state to specified snapshot")
    public ResponseEntity<SnapshotResponse> revert(
            @PathVariable @Parameter(description = "Chain id") String chainId,
            @PathVariable @Parameter(description = "Snapshot id") String snapshotId) {
        log.info("Request to revert chain with id: {}, to snapshot id: {}", chainId, snapshotId);
        var entity = snapshotService.revert(chainId, snapshotId);
        var response = snapshotMapper.asResponse(entity);
        response.setXmlDefinition(null);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    @Operation(description = "Delete all snapshots from specified chain")
    public ResponseEntity<Void> deleteAllByChainId(@PathVariable @Parameter(description = "Chain id") String chainId) {
        log.info("Request to delete all snapshots from chain: {}", chainId);
        snapshotService.deleteAllByChainId(chainId);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @DeleteMapping("/{snapshotId}")
    @Operation(description = "Delete specified snapshot")
    public ResponseEntity<Void> deleteById(@PathVariable @Parameter(description = "Chain id") String chainId,
                                           @PathVariable @Parameter(description = "Snapshot id") String snapshotId) {
        log.info("Request to delete snapshot with id {} in chain {}", snapshotId, chainId);
        snapshotService.deleteById(snapshotId);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

}
