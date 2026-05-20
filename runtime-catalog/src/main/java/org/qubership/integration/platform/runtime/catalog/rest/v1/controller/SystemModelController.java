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
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SystemModelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.SystemModelMapper;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/models")
@Tag(name = "system-model-controller", description = "System Model Controller")
public class SystemModelController {
    private final SystemModelMapper systemModelMapper;
    private final SystemModelService systemModelService;
    private final ActionsLogService actionLogger;

    @Autowired
    public SystemModelController(SystemModelMapper systemModelMapper,
                                 SystemModelService systemModelService,
                                 ActionsLogService actionLogger) {
        this.systemModelMapper = systemModelMapper;
        this.systemModelService = systemModelService;
        this.actionLogger = actionLogger;
    }

    @GetMapping(produces = "application/json")
    @Operation(description = "Get all specifications")
    public ResponseEntity<List<SystemModelDTO>> getModels(@RequestParam(required = false) @Parameter(description = "Filter response by specification group id") String specificationGroupId,
                                                          @RequestParam(required = false) @Parameter(description = "Filter response by service id") String systemId) {
        List<SystemModel> models = new ArrayList<>();

        if (log.isDebugEnabled()) {
            log.debug("Request to get models by system {} and specification group {}", systemId, specificationGroupId);
        }
        if (!StringUtils.isBlank(specificationGroupId)) {
            models = systemModelService.getSystemModelsBySpecificationGroupId(specificationGroupId);
        } else if (!StringUtils.isBlank(systemId)) {
            models = systemModelService.getSystemModelsBySystemId(systemId);
        }
        return ResponseEntity.ok(systemModelMapper.toSystemModelDTOs(models));
    }

    @GetMapping(value = "/{modelId}", produces = "application/json")
    @Operation(description = "Get specific specification")
    public SystemModelDTO getSystemModel(@PathVariable @Parameter(description = "Specification id") String modelId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get system model {}", modelId);
        }
        SystemModel systemModel = systemModelService.getSystemModel(modelId);
        return systemModelMapper.toSystemModelDTO(systemModel);
    }

    @GetMapping(value = "/{modelId}/source", produces = "text/plain")
    @Operation(description = "Get raw contents of specification source")
    public String getSystemModelSource(@PathVariable @Parameter(description = "Specification id") String modelId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get system model {} source", modelId);
        }
        return systemModelService.getMainSystemModelSource(modelId);
    }

    @PostMapping(value = "/deprecated", produces = "application/json")
    @Operation(description = "Make specification deprecated request")
    public ResponseEntity<SystemModelDTO> makeSystemModelDeprecated(@RequestBody @Parameter(description = "Specification id") String modelId) {
        log.info("Request to make system model {} deprecated", modelId);
        SystemModel systemModel = systemModelService.getSystemModel(modelId);
        if (systemModel != null) {
            systemModel.setDeprecated(true);
            systemModel = systemModelService.update(systemModel);
            logSpecAction(systemModel, systemModel.getSpecificationGroup(), LogOperation.DEPRECATE);
            return ResponseEntity.ok(systemModelMapper.toSystemModelDTO(systemModel));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping(value = "/{modelId}", produces = "application/json")
    @Operation(description = "Delete specification")
    public ResponseEntity<Void> deleteSystemModel(@PathVariable @Parameter(description = "Specification id") String modelId) {
        log.info("Request to delete system model {}", modelId);
        SystemModel systemModel = systemModelService.getSystemModel(modelId);
        systemModelService.deleteSystemModel(systemModel);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping(value = "/{modelId}", produces = "application/json")
    @Operation(description = "Partially update specification")
    public ResponseEntity<SystemModelDTO> partiallyUpdateSystemModel(@PathVariable @Parameter(description = "Specification id") String modelId,
                                                                     @RequestBody @Parameter(description = "Specification") SystemModelDTO model) {
        log.info("Request to partially update system model {}", modelId);
        return ResponseEntity.ok(systemModelMapper.toSystemModelDTO(
                systemModelService.partiallyUpdate(systemModelMapper.asEntity(model))));
    }

    @GetMapping(value = "/latest", produces = "application/json")
    @Operation(description = "Get latest created specification in specified service")
    public SystemModelDTO getLatestSystemModel(@RequestParam @Parameter(description = "Service id") String systemId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get latest system {} model", systemId);
        }
        SystemModel systemModel = systemModelService.getLatestSystemModel(systemId);
        return systemModelMapper.toSystemModelDTO(systemModel);
    }

    private void logSpecAction(SystemModel spec, SpecificationGroup group, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SPECIFICATION)
                .entityId(spec.getId())
                .entityName(spec.getName())
                .parentType(group == null ? null : EntityType.SPECIFICATION_GROUP)
                .parentId(group == null ? null : group.getId())
                .parentName(group == null ? null : group.getName())
                .operation(operation)
                .build());
    }
}
