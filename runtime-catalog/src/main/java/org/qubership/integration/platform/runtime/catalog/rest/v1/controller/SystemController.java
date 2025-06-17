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
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemSearchRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.SystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/systems")
@Tag(name = "system-controller", description = "System Controller")
public class SystemController {

    private final SystemService systemService;
    private final SystemMapper systemMapper;

    @Autowired
    public SystemController(SystemService systemService, SystemMapper systemMapper) {
        this.systemService = systemService;
        this.systemMapper = systemMapper;
    }

    @GetMapping(produces = "application/json")
    @Operation(description = "Get all services")
    public List<SystemDTO> getSystems(@RequestParam(required = false, defaultValue = "all") @Parameter(description = "Filter services by type. If \"all\" specified - nothing will be excluded from the response.") String modelType,
                                      @RequestParam(required = false, defaultValue = "false") @Parameter(description = "Whether response will include specifications") boolean withSpec) {
        List<IntegrationSystem> systems;
        if (modelType.equals("all")) {
            systems = withSpec ? systemService.getNotDeprecatedWithSpecs() : systemService.getAll();
        } else {
            systems = systemService.getNotDeprecatedAndByModelType(OperationProtocol.receiveProtocolsFromType(modelType));
        }
        return systemMapper.toResponseDTOs(systems);
    }

    @GetMapping(value = "/{systemId}", produces = "application/json")
    @Operation(description = "Get specific service")
    public SystemDTO getSystem(@PathVariable @Parameter(description = "Service id") String systemId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get system {}", systemId);
        }
        return systemMapper.toDTO(systemService.findById(systemId));
    }

    @PostMapping(value = "/search", produces = "application/json")
    @Operation(description = "Search services request")
    public List<SystemDTO> searchSystems(@RequestBody @Parameter(description = "Service search request object") SystemSearchRequestDTO systemSearchRequestDTO) {
        return systemMapper.toResponseDTOs(systemService.searchSystems(systemSearchRequestDTO));
    }

    @PostMapping(value = "/filter", produces = "application/json")
    @Operation(description = "Filter services request")
    public List<SystemDTO> filterSystems(@RequestBody @Parameter(description = "Service filter request object") List<FilterRequestDTO> systemFilterRequestDTOList) {
        List<IntegrationSystem> systemsFilterResult = systemService.findByFilterRequest(systemFilterRequestDTOList);

        return systemMapper.toResponseDTOs(systemsFilterResult)
                .stream()
                .sorted(Comparator.comparing(SystemDTO::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    @PostMapping
    @Operation(description = "Create service")
    public ResponseEntity<SystemDTO> createSystem(@RequestBody @Parameter(description = "Service modifying request object") SystemRequestDTO systemDto) {
        IntegrationSystem system = systemMapper.toSystem(systemDto);
        system = systemService.create(system);
        return new ResponseEntity<>(systemMapper.toDTO(system), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{systemId}", produces = "application/json")
    @Operation(description = "Modify specified service")
    public ResponseEntity<SystemDTO> updateSystem(@PathVariable @Parameter(description = "Service id") String systemId,
                                                  @RequestBody @Parameter(description = "Service modifying request object") SystemRequestDTO systemDto) {
        log.info("Request to update system {}", systemId);
        IntegrationSystem system = systemService.getByIdOrNull(systemId);
        if (system != null) {
            String name = system.getName();
            systemMapper.mergeWithoutLabels(systemDto, system);
            systemService.replaceLabels(system, systemMapper.asLabelRequests(systemDto.getLabels()));
            system = systemService.save(system);
            if (!system.getName().equals(name)) {
                systemService.updateSystemModelCompiledLibraryAsync(system);
            }
            return ResponseEntity.ok(systemMapper.toDTO(system));
        } else {
            return createSystem(systemDto);
        }
    }

    @PatchMapping(value = "/{systemId}", produces = "application/json")
    @Operation(description = "Partially update service")
    public ResponseEntity<SystemDTO> updateSyncStatus(@PathVariable @Parameter(description = "Service id") String systemId,
                                                      @RequestBody @Parameter(description = "Service modifying request object") SystemRequestDTO systemDto) {
        log.info("Request to update system sync status {}", systemId);
        IntegrationSystem system = systemService.getByIdOrNull(systemId);
        if (system != null) {
            String name = system.getName();
            systemMapper.patchMergeWithoutLabels(systemDto, system);
            systemService.replaceLabels(system, systemMapper.asLabelRequests(systemDto.getLabels()));
            system = systemService.save(system);
            if (!system.getName().equals(name)) {
                systemService.updateSystemModelCompiledLibraryAsync(system);
            }
            return ResponseEntity.ok(systemMapper.toDTO(system));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping(value = "/{systemId}")
    @Operation(description = "Delete specified service")
    public void deleteSystem(@PathVariable @Parameter(description = "Service id") String systemId) {
        log.info("Request to delete system {}", systemId);
        systemService.delete(systemId);
    }
}
