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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementSearchCriteria;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRedeployRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.service.ChainRolesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@RestController
@Validated
@RequestMapping(value = {"/v1/catalog/chains/roles", "/v1/catalog/chains/access-control"}, produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "chain-roles-controller", description = "Chain Roles Controller")
public class ChainRolesController {

    private final ChainRolesService chainRolesService;

    @Autowired
    public ChainRolesController(ChainRolesService chainRolesService) {
        this.chainRolesService = chainRolesService;
    }

    @PostMapping("")
    @Operation(description = "Get all chains with http trigger roles")
    public ResponseEntity<ChainRolesResponse> findBySearchRequest(@RequestBody @Parameter(description = "Search criteria for chain element") ChainElementSearchCriteria request,
                                                                  @RequestParam(required = false, defaultValue = "false") @Parameter(description = "Whether include only http triggers with selected implemented service") boolean isImplementedOnly) {
        ChainRolesResponse response = chainRolesService.findAllChainByHttpTrigger(request, isImplementedOnly);
        return ResponseEntity.ok(response);
    }

    @PutMapping("")
    @Operation(description = "Make a bulk update on roles configuration for http triggers")
    @ApiResponse(responseCode = "204", description = "No Content")
    public ResponseEntity<Void> updateRoles(@RequestBody @Parameter(description = "Http trigger roles update request object") List<@Valid UpdateRolesRequest> request) {
        chainRolesService.updateRoles(request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/redeploy")
    @Operation(description = "Make a bulk redeploy for chains specified in the request")
    @ApiResponse(responseCode = "204", description = "No Content")
    public ResponseEntity<Void> bulkRedeploy(@RequestBody @Parameter(description = "Chain redeploy request object") List<ChainRedeployRequest> request) {
        chainRolesService.redeploy(request);
        return ResponseEntity.noContent().build();
    }
}
