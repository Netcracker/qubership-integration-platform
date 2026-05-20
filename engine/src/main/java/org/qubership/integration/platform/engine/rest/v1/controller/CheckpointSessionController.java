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

package org.qubership.integration.platform.engine.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.rest.v1.dto.checkpoint.CheckpointSessionDTO;
import org.qubership.integration.platform.engine.rest.v1.mapper.SessionInfoMapper;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping(value = "/v1/engine/chains/{chainId}",
    produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "checkpoint-session-controller", description = "Checkpoint Session Controller")
public class CheckpointSessionController {
    private final CheckpointSessionService checkpointSessionService;
    private final SessionInfoMapper sessionInfoMapper;

    @Autowired
    public CheckpointSessionController(CheckpointSessionService checkpointSessionService,
                                       SessionInfoMapper sessionInfoMapper) {
        this.checkpointSessionService = checkpointSessionService;
        this.sessionInfoMapper = sessionInfoMapper;
    }

    @PostMapping("/sessions/{sessionId}/retry")
    @Operation(description = "Execute chain retry from saved latest non-failed checkpoint", extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}))
    public ResponseEntity<Void> retryFromLastCheckpoint(
        @PathVariable @Parameter(description = "Chain id") String chainId,
        @PathVariable @Parameter(description = "Session id") String sessionId,
        @RequestHeader(required = false, defaultValue = "") @Parameter(description = "If passed, Authorization header will be replaced with this value") String authorization,
        @RequestHeader(required = false, defaultValue = "false") @Parameter(description = "Enable TraceMe header, which will force session to be logged") boolean traceMe,
        @RequestBody(required = false) @Parameter(description = "If passed, request body will be replaced with this value") String body
    ) {
        log.info("Request to retry session {}", sessionId);
        checkpointSessionService.retryFromLastCheckpoint(chainId, sessionId, body, toAuthSupplier(authorization), traceMe);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/sessions/{sessionId}/checkpoint-elements/{checkpointElementId}/retry")
    @Operation(description = "Execute chain retry from specified non-failed checkpoint", extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}))
    public ResponseEntity<Void> retryFromCheckpoint(
        @PathVariable @Parameter(description = "Chain id") String chainId,
        @PathVariable @Parameter(description = "Session id") String sessionId,
        @PathVariable @Parameter(description = "Checkpoint element id (could be found on chain graph in checkpoint element itself)") String checkpointElementId,
        @RequestHeader(required = false, defaultValue = "") @Parameter(description = "If passed, Authorization header will be replaced with this value") String authorization,
        @RequestHeader(required = false, defaultValue = "false") @Parameter(description = "Enable TraceMe header, which will force session to be logged") boolean traceMe,
        @RequestBody(required = false) @Parameter(description = "If passed, request body will be replaced with this value") String body
    ) {
        log.info("Request to retry session {} from checkpoint {}", sessionId, checkpointElementId);
        checkpointSessionService.retryFromCheckpoint(chainId, sessionId, checkpointElementId, body, toAuthSupplier(authorization), traceMe);
        return ResponseEntity.accepted().build();
    }

    @Transactional("checkpointTransactionManager")
    @GetMapping("/sessions/failed")
    @Operation(description = "List all failed sessions with available checkpoints for specified chain", extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}))
    public ResponseEntity<List<CheckpointSessionDTO>> getFailedChainSessionsInfo(@PathVariable @Parameter(description = "Chain id") String chainId) {
        return ResponseEntity.ok(
            sessionInfoMapper.asDTO(checkpointSessionService.findAllFailedChainSessionsInfo(chainId)));
    }

    private static @NotNull Supplier<Pair<String, String>> toAuthSupplier(String authorization) {
        return () -> Pair.of(HttpHeaders.AUTHORIZATION, authorization);
    }
}
