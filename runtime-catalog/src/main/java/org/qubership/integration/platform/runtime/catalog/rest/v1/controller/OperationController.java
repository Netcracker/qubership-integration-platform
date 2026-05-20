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

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.OperationDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.OperationInfoDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.OperationMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.OperationSchemasMapper;
import org.qubership.integration.platform.runtime.catalog.service.OperationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/operations")
@Tag(name = "operation-controller", description = "Operation Controller")
public class OperationController {
    private final OperationService operationService;
    private final OperationMapper operationMapper;
    private final OperationSchemasMapper operationSchemasMapper;

    @Autowired
    public OperationController(OperationService operationService,
                               OperationMapper operationMapper,
                               OperationSchemasMapper operationSchemasMapper) {
        this.operationService = operationService;
        this.operationMapper = operationMapper;
        this.operationSchemasMapper = operationSchemasMapper;
    }

    @GetMapping(value = "/{operationId}", produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(description = "Get specific operation")
    public OperationDTO getOperation(@PathVariable @Parameter(description = "Operation id") String operationId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get operation {}", operationId);
        }
        Operation operation = operationService.getOperation(operationId);
        return operationMapper.toOperationDTO(operation);
    }

    @GetMapping(produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(description = "Get list of operations from specification")
    public ResponseEntity<List<OperationDTO>> getOperationsByModel(
            @RequestParam @Parameter(description = "Specification id") String modelId,
            @RequestParam(required = false, defaultValue = "0") @Parameter(description = "Which entity order number should we start from") int offset,
            @RequestParam(required = false, defaultValue = "20") @Parameter(description = "Amount of entities received at a time") int count,
            @RequestParam(required = false, defaultValue = "") @Parameter(description = "Search value") String searchFilter,
            @RequestParam(required = false, defaultValue = "path, method, name") @Parameter(description = "Column names which will be used to sort response, separated by comma") List<String> sortColumns) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get operation by model {}", modelId);
        }
        List<Operation> operations;
        try {
            operations = operationService.getOperationsByModel(
                    modelId,
                    offset,
                    count,
                    searchFilter,
                    sortColumns);
        } catch (InvalidDataAccessResourceUsageException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(operationMapper.toOperationDTOs(operations));
    }

    @GetMapping(value = "/{operationId}/schemas", produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(description = "Get specific operation with schemas")
    public Object getOperationWithSchemas(@PathVariable @Parameter(description = "Operation id") String operationId,
                                          @RequestParam(required = false, defaultValue = "light") @Parameter(description = "If \"light\" was passed - response will be without part of request and response schemas. Otherwise all data will be included in response.") String mode) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get operation {} with schemas", operationId);
        }
        Operation operation;
        if (mode.equals("light")) {
            operation = operationService.getOperationLight(operationId);
            return operationSchemasMapper.toOperationSchemasDTO(operation);
        }
        operation = operationService.getOperation(operationId);
        return operationSchemasMapper.toOperationSchemasDTO(operation);
    }

    @GetMapping(value = "/{operationId}/info", produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(description = "Get specific operation info")
    public ResponseEntity<OperationInfoDTO> getInfo(@PathVariable @Parameter(description = "Operation id") String operationId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get operation {} info", operationId);
        }
        return ResponseEntity.ok(operationMapper.toOperationInfoDTO(operationService.getOperation(operationId)));
    }

    @GetMapping(value = "/{operationId}/specification", produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(description = "Get specification part related to specified operation")
    public Object getSpecification(@PathVariable @Parameter(description = "Operation id") String operationId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get operation {} specification", operationId);
        }
        return operationService.getSpecification(operationId);
    }

    @GetMapping(value = "/{operationId}/schemas/request", produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(description = "Get request schema from specification related to specified operation")
    public JsonNode getOperationRequestSchema(@PathVariable @Parameter(description = "Operation id") String operationId,
                                              @RequestParam(defaultValue = "application/json") @Parameter(description = "Content-type for schema") String contentType) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get operation {} request schema", operationId);
        }
        return operationService.getRequestSchema(operationId, contentType);
    }

    @GetMapping(value = "/{operationId}/schemas/response", produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(description = "Get response schema from specification related to specified operation")
    public JsonNode getOperationResponseSchema(@PathVariable @Parameter(description = "Operation id") String operationId,
                                               @RequestParam(defaultValue = "application/json") @Parameter(description = "Content-type for schema") String contentType,
                                               @RequestParam(required = false, defaultValue = "200") @Parameter(description = "Response code for schema") String responseCode) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get operation {} response schema", operationId);
        }
        return operationService.getResponseSchema(operationId, contentType, responseCode);
    }
}
