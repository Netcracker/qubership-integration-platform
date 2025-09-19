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
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.rest.v1.dto.checkpoint.CheckpointSessionDTO;
import org.qubership.integration.platform.engine.rest.v1.mapper.SessionInfoMapper;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Path("/v1/engine/chains/{chainId}")
@Produces(MediaType.APPLICATION_JSON)
// TODO Enable CORS with origins *
//@CrossOrigin(origins = "*")
@Tag(name = "checkpoint-session-controller", description = "Checkpoint Session Controller")
public class CheckpointSessionController {
    private final CheckpointSessionService checkpointSessionService;
    private final SessionInfoMapper sessionInfoMapper;

    @Inject
    public CheckpointSessionController(CheckpointSessionService checkpointSessionService,
                                       SessionInfoMapper sessionInfoMapper) {
        this.checkpointSessionService = checkpointSessionService;
        this.sessionInfoMapper = sessionInfoMapper;
    }

    @POST
    @Path("/sessions/{sessionId}/retry")
    @Operation(
            description = "Execute chain retry from saved latest non-failed checkpoint",
            extensions = {@Extension(name = "x-api-kind", value = "bwc")}
    )
    public RestResponse<Void> retryFromLastCheckpoint(
            @PathParam("chainId")
            @Parameter(description = "Chain id")
            String chainId,

            @PathParam("sessionId")
            @Parameter(description = "Session id")
            String sessionId,

            @HeaderParam("authorization")
            @DefaultValue("")
            @Parameter(description = "If passed, Authorization header will be replaced with this value")
            String authorization,

            @HeaderParam("traceMe")
            @DefaultValue("false")
            @Parameter(description = "Enable TraceMe header, which will force session to be logged")
            boolean traceMe,

            @Parameter(description = "If passed, request body will be replaced with this value")
            String body
    ) {
        log.info("Request to retry session {}", sessionId);
        checkpointSessionService.retryFromLastCheckpoint(chainId, sessionId, body, toAuthSupplier(authorization), traceMe);
        return RestResponse.accepted();
    }

    @POST
    @Path("/sessions/{sessionId}/checkpoint-elements/{checkpointElementId}/retry")
    @Operation(
            description = "Execute chain retry from specified non-failed checkpoint",
            extensions = {@Extension(name = "x-api-kind", value = "bwc")}
    )
    public RestResponse<Void> retryFromCheckpoint(
            @PathParam("chainId")
            @Parameter(description = "Chain id")
            String chainId,

            @PathParam("sessionId")
            @Parameter(description = "Session id") String sessionId,

            @PathParam("checkpointElementId")
            @Parameter(description = "Checkpoint element id (could be found on chain graph in checkpoint element itself)")
            String checkpointElementId,

            @HeaderParam("authorization")
            @DefaultValue("")
            @Parameter(description = "If passed, Authorization header will be replaced with this value")
            String authorization,

            @HeaderParam("traceMe")
            @DefaultValue("false")
            @Parameter(description = "Enable TraceMe header, which will force session to be logged")
            boolean traceMe,

            @Parameter(description = "If passed, request body will be replaced with this value")
            String body
    ) {
        log.info("Request to retry session {} from checkpoint {}", sessionId, checkpointElementId);
        checkpointSessionService.retryFromCheckpoint(chainId, sessionId, checkpointElementId, body, toAuthSupplier(authorization), traceMe);
        return RestResponse.accepted();
    }

    @Transactional("checkpointTransactionManager")
    @GET
    @Path("/sessions/failed")
    @Operation(
            description = "List all failed sessions with available checkpoints for specified chain",
            extensions = {@Extension(name = "x-api-kind", value = "bwc")}
    )
    public RestResponse<List<CheckpointSessionDTO>> getFailedChainSessionsInfo(
            @PathParam("chainId")
            @Parameter(description = "Chain id")
            String chainId
    ) {
        Collection<SessionInfo> sessions = checkpointSessionService.findAllFailedChainSessionsInfo(chainId);
        List<CheckpointSessionDTO> dtos = new ArrayList<>();
        return RestResponse.ok(dtos);
    }

    private static @NotNull Supplier<Pair<String, String>> toAuthSupplier(String authorization) {
        return () -> Pair.of(HttpHeaders.AUTHORIZATION, authorization);
    }
}
