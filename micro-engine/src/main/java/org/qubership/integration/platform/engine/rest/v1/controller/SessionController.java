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

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.rest.v1.dto.checkpoint.CheckpointSessionDTO;
import org.qubership.integration.platform.engine.rest.v1.mapper.SessionInfoMapper;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Path("/v1/engine/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "session-controller", description = "Session Controller")
public class SessionController {
    private final CheckpointSessionService checkpointSessionService;
    private final SessionInfoMapper sessionInfoMapper;

    @Inject
    public SessionController(
            CheckpointSessionService checkpointSessionService,
            SessionInfoMapper sessionInfoMapper
    ) {
        this.checkpointSessionService = checkpointSessionService;
        this.sessionInfoMapper = sessionInfoMapper;
    }

    @GET
    @Transactional
    @Operation(description = "List all sessions with available checkpoints by their ids")
    public RestResponse<List<CheckpointSessionDTO>> findSessions(
            @QueryParam("ids")
            @DefaultValue("")
            @Parameter(description = "List of the session ids separated by comma")
            List<String> ids
    ) {
        // TODO [migration to quarkus] check that ids can be null otherwise use ids directly
        List<String> identifiers = Optional.ofNullable(ids).orElse(Collections.emptyList());
        Collection<SessionInfo> sessions = checkpointSessionService.findSessions(identifiers);
        List<CheckpointSessionDTO> dtos = sessions.stream().map(sessionInfoMapper::asDTO).toList();
        return RestResponse.ok(dtos);
    }
}
