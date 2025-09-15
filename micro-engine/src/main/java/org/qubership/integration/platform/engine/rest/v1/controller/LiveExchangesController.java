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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;
import org.qubership.integration.platform.engine.rest.v1.dto.LiveExchangeDTO;
import org.qubership.integration.platform.engine.service.LiveExchangesService;
import jakarta.inject.Inject;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Slf4j
@Path("/v1/engine/live-exchanges")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "live-exchanges-controller", description = "Live Exchanges Controller")
public class LiveExchangesController {
    private final LiveExchangesService liveExchangesService;

    @Inject
    public LiveExchangesController(LiveExchangesService liveExchangesService) {
        this.liveExchangesService = liveExchangesService;
    }

    @GET
    @Operation(description = "Get top N running exchanges ordered by execution time DESC")
    public RestResponse<List<LiveExchangeDTO>> getLiveExchanges(
            @QueryParam("limit")
            @DefaultValue("10")
            @Positive
            @Parameter(description = "Amount of exchanges to view") Integer limit
    ) {
        List<LiveExchangeDTO> result = liveExchangesService.getTopLiveExchanges(limit);
        if (CollectionUtils.isEmpty(result)) {
            return RestResponse.noContent();
        }
        return RestResponse.ok(result);
    }

    @DELETE
    @Path("/{deploymentId}/{exchangeId}")
    @Operation(description = "Try to kill specified exchange")
    public RestResponse<Void> killExchange(
            @PathParam("deploymentId")
            @NotBlank
            @Parameter(description = "Deployment ID")
            String deploymentId,

            @PathParam("exchangeId")
            @NotBlank
            @Parameter(description = "Exchange ID")
            String exchangeId
    ) {
        liveExchangesService.killLiveExchangeById(deploymentId, exchangeId);
        return RestResponse.accepted();
    }
}
