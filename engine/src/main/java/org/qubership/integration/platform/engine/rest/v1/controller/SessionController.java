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
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.rest.v1.dto.checkpoint.CheckpointSessionDTO;
import org.qubership.integration.platform.engine.rest.v1.mapper.SessionInfoMapper;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping(
        value = "/v1/engine/sessions",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "session-controller", description = "Session Controller")
public class SessionController {
    private final CheckpointSessionService checkpointSessionService;
    private final SessionInfoMapper sessionInfoMapper;

    @Autowired
    public SessionController(
            CheckpointSessionService checkpointSessionService,
            SessionInfoMapper sessionInfoMapper
    ) {
        this.checkpointSessionService = checkpointSessionService;
        this.sessionInfoMapper = sessionInfoMapper;
    }

    @GetMapping()
    @Transactional("checkpointTransactionManager")
    @Operation(description = "List all sessions with available checkpoints by their ids")
    public ResponseEntity<List<CheckpointSessionDTO>> findSessions(@RequestParam(required = false) @Parameter(description = "List of the session ids separated by comma") List<String> ids) {
        return ResponseEntity.ok(checkpointSessionService.findSessions(Optional.ofNullable(ids).orElse(Collections.emptyList()))
                .stream().map(sessionInfoMapper::asDTO).toList());
    }
}
