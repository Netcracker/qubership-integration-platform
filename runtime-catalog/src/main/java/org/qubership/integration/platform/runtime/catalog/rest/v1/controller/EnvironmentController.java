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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentDTO;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentRequestDTO;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.EnvironmentMapper;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@Validated
@CrossOrigin(origins = "*")
@RequestMapping("/v1/systems/{systemId}/environments")
@Tag(name = "environment-controller", description = "Environment Controller")
public class EnvironmentController {

    private static final String INTERNAL_SYSTEM_ENVIRONMENT_UNIQUE_MESSAGE = "Can't put more than one environment to 'internal' system";
    private static final String UNIQUE_LABEL_WITHIN_SINGLE_SYSTEM_MESSAGE = "Label should be unique within single system: ";
    private final EnvironmentService environmentService;
    private final SystemService systemService;
    private final EnvironmentMapper environmentMapper;

    @Autowired
    public EnvironmentController(EnvironmentService environmentService,
                                 EnvironmentMapper environmentMapper,
                                 SystemService systemService) {
        this.environmentService = environmentService;
        this.environmentMapper = environmentMapper;
        this.systemService = systemService;
    }

    @GetMapping(produces = "application/json")
    @Operation(description = "Get all environments for specified service")
    public List<EnvironmentDTO> getEnvironments(@PathVariable @Parameter(description = "Service id") String systemId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get environments for system: {}", systemId);
        }
        return environmentMapper.toDTOs(environmentService.getEnvironmentsForSystem(systemId));
    }

    @GetMapping(value = "/{environmentId}", produces = "application/json")
    @Operation(description = "Get specific environment from service")
    public EnvironmentDTO getEnvironment(@PathVariable @Parameter(description = "Service id") String systemId,
                                         @PathVariable @Parameter(description = "Environment id") String environmentId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get environments for system: {}", systemId);
        }
        return environmentMapper.toDTO(environmentService.getByIdForSystem(systemId, environmentId));
    }

    @PostMapping
    @Operation(description = "Create new environment for a service")
    public ResponseEntity<EnvironmentDTO> createEnvironment(@PathVariable @Parameter(description = "Service id") String systemId,
                                                            @RequestBody @Parameter(description = "Request object for environment creation") EnvironmentRequestDTO environmentRequestDTO) {
        log.info("Request to create environment for system: {}", systemId);
        checkEnvironmentLabels(systemId, environmentRequestDTO.getLabels());
        if (environmentRequestDTO.getProperties() != null) {
            environmentRequestDTO.setProperties(environmentRequestDTO.getProperties());
        }
        Environment environment = environmentMapper.toEnvironment(environmentRequestDTO);
        IntegrationSystem system = systemService.getByIdOrNull(systemId);
        if (IntegrationSystemType.INTERNAL.equals(system.getIntegrationSystemType())) {
            if (!system.getEnvironments().isEmpty()) {
                throw new BadRequestException(INTERNAL_SYSTEM_ENVIRONMENT_UNIQUE_MESSAGE);
            }
        }
        environment = environmentService.create(environment, system);
        return new ResponseEntity<>(environmentMapper.toDTO(environment), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{environmentId}", produces = "application/json")
    @Operation(description = "Update existing environment of a service")
    public ResponseEntity<EnvironmentDTO> updateEnvironment(@PathVariable @Parameter(description = "Service id") String systemId,
                                                            @PathVariable @Parameter(description = "Environment id") String environmentId,
                                                            @RequestBody @Parameter(description = "Request object for environment modifying") EnvironmentRequestDTO environmentRequestDTO) {
        log.info("Request to update environment {} for system {}", environmentId, systemId);
        Environment environment = environmentService.getByIdForSystemOrElseNull(systemId, environmentId);
        if (environment != null) {
            checkEnvironmentLabels(systemId, environmentRequestDTO.getLabels(), environmentId);
            environmentRequestDTO.setProperties(environmentRequestDTO.getProperties());
            environmentMapper.merge(environmentRequestDTO, environment);
            environment = environmentService.update(environment);
            return ResponseEntity.ok(environmentMapper.toDTO(environment));
        } else {
            return createEnvironment(systemId, environmentRequestDTO);
        }
    }

    @DeleteMapping(value = "/{environmentId}")
    @Operation(description = "Delete existing environment")
    public void deleteEnvironment(@PathVariable @Parameter(description = "Service id") String systemId,
                                  @PathVariable @Parameter(description = "Environment id") String environmentId) {
        log.info("Request to delete environment {} for system {}", environmentId, systemId);
        environmentService.deleteEnvironment(systemId, environmentId);
    }

    private void checkEnvironmentLabels(String systemId, List<EnvironmentLabel> labels, String excludeEnvironmentId) {
        if (labels == null) {
            return;
        }

        for (EnvironmentLabel label : labels) {
            List<Environment> labelEnvs = environmentService.getEnvironmentsByLabel(systemId, label);
            if (!labelEnvs.isEmpty()
                    && (labelEnvs.size() != 1
                        || !labelEnvs.get(0).getId().equals(excludeEnvironmentId))) {
                throw new BadRequestException(UNIQUE_LABEL_WITHIN_SINGLE_SYSTEM_MESSAGE + label);
            }
        }
    }

    private void checkEnvironmentLabels(String systemId, List<EnvironmentLabel> labels) {
        checkEnvironmentLabels(systemId, labels, null);
    }
}
