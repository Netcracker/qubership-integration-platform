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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SpecificationGroupCreationRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SpecificationGroupDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SpecificationGroupRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.SpecificationGroupMapper;
import org.qubership.integration.platform.runtime.catalog.service.SpecificationGroupService;
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
public class SpecificationGroupController {
    private final SpecificationGroupService specificationGroupService;
    private final SpecificationGroupMapper specificationGroupMapper;

    @Autowired
    public SpecificationGroupController(SpecificationGroupService specificationGroupService,
                                        SpecificationGroupMapper specificationGroupMapper) {
        this.specificationGroupService = specificationGroupService;
        this.specificationGroupMapper = specificationGroupMapper;
    }

    @GetMapping(produces = "application/json")
    @Operation(description = "Get all specification groups for specified service")
    public ResponseEntity<List<SpecificationGroupDTO>> getSpecificationGroups(@RequestParam @Parameter(description = "Service id") String systemId) {
        return ResponseEntity.ok(specificationGroupMapper.toSpecificationGroupDTOs(specificationGroupService.getSpecificationGroups(systemId)));
    }

    @DeleteMapping(value = "/{specificationGroupId}", produces = "application/json")
    @Operation(description = "Delete specification group")
    public void deleteSpecificationGroup(@PathVariable @Parameter(description = "Specification group id") String specificationGroupId) {
        log.info("Request to delete specification group {}", specificationGroupId);
        specificationGroupService.delete(specificationGroupId);
    }

    @PatchMapping(value = "/{specificationGroupId}", produces = "application/json")
    @Operation(description = "Update synchronization toggle on a specification group")
    public ResponseEntity<SpecificationGroupDTO> updateSyncStatus(
            @PathVariable @Parameter(description = "Specification group id") String specificationGroupId,
            @RequestBody @Parameter(description = "Specification group modification object") SpecificationGroupRequestDTO specificationGroupDTO) {
        SpecificationGroup specificationGroup = specificationGroupService.getById(specificationGroupId);
        if (specificationGroup != null) {
            specificationGroupMapper.mergeWithoutLabels(specificationGroupDTO, specificationGroup);
            specificationGroup = specificationGroupService.update(specificationGroup, specificationGroupMapper.asLabelRequests(specificationGroupDTO.getLabels()));
            return ResponseEntity.ok(specificationGroupMapper.toSpecificationGroupDTO(specificationGroup));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping
    @Operation(description = "Create specification group")
    public ResponseEntity<SpecificationGroupDTO> createSpecificationGroup(
            @RequestBody @Parameter(description = "Specification group create request object") SpecificationGroupCreationRequestDTO params
    ) {
        SpecificationGroup specificationGroup = specificationGroupService.createAndSaveSpecificationGroup(
                params.getSystemId(), params.getName(), params.getDescription(), params.getUrl(),
                params.isSynchronization());
        SpecificationGroupDTO response = specificationGroupMapper.toSpecificationGroupDTO(specificationGroup);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.getId())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }
}
