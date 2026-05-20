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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.LiveExchangeExtDTO;
import org.qubership.integration.platform.runtime.catalog.service.LiveExchangesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value = "/v1/catalog/live-exchanges", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "live-exchanges-controller", description = "Live Exchanges Controller")
public class LiveExchangesController {
    private final LiveExchangesService liveExchangesService;

    @Autowired
    public LiveExchangesController(LiveExchangesService liveExchangesService) {
        this.liveExchangesService = liveExchangesService;
    }

    @GetMapping
    @Operation(description = "Get top N running sessions live exchanges ordered by execution time DESC from all running engines")
    public ResponseEntity<List<LiveExchangeExtDTO>> getLiveExchanges(@RequestParam(required = false, defaultValue = "10") @Positive @Parameter(description = "Amount of entries to view") Integer limit) {
        List<LiveExchangeExtDTO> result = liveExchangesService.getTopLongLiveExchanges(limit);
        if (CollectionUtils.isEmpty(result)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{podIp}/{deploymentId}/{exchangeId}")
    @Operation(description = "Try to kill specified live exchange")
    public ResponseEntity<Void> killExchange(@PathVariable @NotBlank @Parameter(description = "Engine pod ip") String podIp,
                                             @PathVariable @NotBlank @Parameter(description = "Deployment ID") String deploymentId,
                                             @PathVariable @NotBlank @Parameter(description = "Exchange ID") String exchangeId) {
        liveExchangesService.sendKillExchangeRequest(podIp, deploymentId, exchangeId);
        return ResponseEntity.accepted().build();
    }

}
