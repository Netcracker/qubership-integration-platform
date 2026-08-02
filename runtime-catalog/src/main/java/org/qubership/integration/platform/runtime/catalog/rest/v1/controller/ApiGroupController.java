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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupCreationRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ApiGroupMapper;
import org.qubership.integration.platform.runtime.catalog.service.ApiGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/specificationGroups")
@Tag(name = "specification-group-controller", description = "Specification Group Controller")
public class ApiGroupController {
    private final ApiGroupService apiGroupService;
    private final ApiGroupMapper apiGroupMapper;

    @Autowired
    public ApiGroupController(ApiGroupService apiGroupService,
                              ApiGroupMapper apiGroupMapper) {
        this.apiGroupService = apiGroupService;
        this.apiGroupMapper = apiGroupMapper;
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "getSpecificationGroups", description = "Get all API groups for specified service")
    public ResponseEntity<List<ApiGroupDTO>> getApiGroups(@RequestParam @Parameter(description = "Service id") String systemId) {
        return ResponseEntity.ok(apiGroupMapper.toApiGroupDTOs(apiGroupService.getSpecificationGroups(systemId)));
    }

    @DeleteMapping(value = "/{specificationGroupId}", produces = "application/json")
    @Operation(operationId = "deleteSpecificationGroup", description = "Delete API group")
    public void deleteApiGroup(@PathVariable @Parameter(description = "API group id") String specificationGroupId) {
        log.info("Request to delete API group {}", specificationGroupId);
        apiGroupService.delete(specificationGroupId);
    }

    @PatchMapping(value = "/{specificationGroupId}", produces = "application/json")
    @Operation(description = "Update synchronization toggle on an API group")
    public ResponseEntity<ApiGroupDTO> updateSyncStatus(
            @PathVariable @Parameter(description = "API group id") String specificationGroupId,
            @RequestBody @Parameter(description = "API group modification object") ApiGroupRequestDTO apiGroupDTO) {
        ApiGroup apiGroup = apiGroupService.getById(specificationGroupId);
        if (apiGroup != null) {
            apiGroupMapper.mergeWithoutLabels(apiGroupDTO, apiGroup);
            apiGroup = apiGroupService.update(apiGroup, apiGroupMapper.asLabelRequests(apiGroupDTO.getLabels()));
            return ResponseEntity.ok(apiGroupMapper.toApiGroupDTO(apiGroup));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping
    @Operation(operationId = "createSpecificationGroup", description = "Create API group")
    public ResponseEntity<ApiGroupDTO> createApiGroup(
            @RequestBody @Parameter(description = "API group create request object") ApiGroupCreationRequestDTO params
    ) {
        ApiGroup apiGroup = apiGroupService.createAndSaveSpecificationGroup(
                params.getSystemId(), params.getName(), params.getDescription(), params.getUrl(),
                params.isSynchronization());
        ApiGroupDTO response = apiGroupMapper.toApiGroupDTO(apiGroup);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.getId())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }
}
