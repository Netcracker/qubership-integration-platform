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

package org.qubership.integration.platform.variables.management.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.ImportInstructionsExecutionResult;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.exportimport.instructions.ImportInstruction;
import org.qubership.integration.platform.variables.management.rest.v1.dto.instructions.ImportInstructionsFilterRequest;
import org.qubership.integration.platform.variables.management.rest.v1.dto.instructions.ImportInstructionsSearchRequest;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.instructions.ImportInstructionDTO;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.instructions.ImportInstructionRequest;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.instructions.ImportInstructionsDTO;
import org.qubership.integration.platform.variables.management.rest.v1.mapper.ImportInstructionRequestMapper;
import org.qubership.integration.platform.variables.management.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.variables.management.service.exportimport.instructions.mapper.ImportInstructionsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v1/common-variables/import-instructions")
@CrossOrigin(origins = "*")
@Tag(name = "import-instructions-controller", description = "Import Instructions Controller")
public class ImportInstructionsController {

    private final ImportInstructionsService importInstructionsService;
    private final ImportInstructionsMapper importInstructionsMapper;
    private final ImportInstructionRequestMapper importInstructionRequestMapper;

    @Autowired
    public ImportInstructionsController(
            ImportInstructionsService importInstructionsService,
            ImportInstructionsMapper importInstructionsMapper,
            ImportInstructionRequestMapper importInstructionRequestMapper
    ) {
        this.importInstructionsService = importInstructionsService;
        this.importInstructionsMapper = importInstructionsMapper;
        this.importInstructionRequestMapper = importInstructionRequestMapper;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Get all import instructions for common variables")
    public ResponseEntity<ImportInstructionsDTO> getImportInstructions() {
        List<ImportInstruction> importInstructions = importInstructionsService.getImportInstructions();
        return ResponseEntity.ok(importInstructionsMapper.asDto(importInstructions));
    }

    @GetMapping(path = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Get all import instructions for common variables in the configuration format")
    public ResponseEntity<ImportInstructionsConfig> getImportInstructionsConfig() {
        return ResponseEntity.ok(importInstructionsService.getImportInstructionsConfig());
    }

    @PostMapping(path = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Search for import instructions")
    public ResponseEntity<ImportInstructionsDTO> searchImportInstructions(
            @RequestBody @Parameter(description = "Import instructions search request object") ImportInstructionsSearchRequest searchRequest
    ) {
        List<ImportInstruction> importInstructions = importInstructionsService
                .searchImportInstructions(searchRequest.getSearchCondition());
        return ResponseEntity.ok(importInstructionsMapper.asDto(importInstructions));
    }

    @PostMapping(path = "/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Filter import instructions")
    public ResponseEntity<ImportInstructionsDTO> filterImportInstructions(
            @RequestBody @Parameter(description = "Import instructions filter request object") List<ImportInstructionsFilterRequest> filterRequests
    ) {
        List<ImportInstruction> importInstructions = importInstructionsService.getImportInstructions(filterRequests);
        return ResponseEntity.ok(importInstructionsMapper.asDto(importInstructions));
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(description = "Upload import instructions configuration from file")
    public ResponseEntity<List<ImportInstructionsExecutionResult>> uploadImportInstructionsConfig(
            @RequestParam("file") @Parameter(description = "Yaml file") MultipartFile file,
            @RequestHeader(value = "labels", required = false) @Parameter(description = "List of labels that should be added on uploaded instructions") List<String> labels
    ) {
        return ResponseEntity.ok(importInstructionsService.uploadImportInstructions(file, labels));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Create new import instruction")
    public ResponseEntity<ImportInstructionDTO> addImportInstruction(
            @RequestBody @Valid @Parameter(description = "Create import instructions request object") ImportInstructionRequest importInstructionRequest
    ) {
        ImportInstruction importInstruction = importInstructionRequestMapper.toEntity(importInstructionRequest);
        importInstruction = importInstructionsService.addImportInstruction(importInstruction);
        return ResponseEntity.ok(importInstructionsMapper.entityToDto(importInstruction));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Update existing import instruction")
    public ResponseEntity<ImportInstructionDTO> updateImportInstruction(
            @RequestBody @Valid @Parameter(description = "Update import instruction request object") ImportInstructionRequest importInstructionRequest
    ) {
        ImportInstruction importInstruction = importInstructionRequestMapper.toEntity(importInstructionRequest);
        importInstruction = importInstructionsService.updateImportInstruction(importInstruction);
        return ResponseEntity.ok(importInstructionsMapper.entityToDto(importInstruction));
    }

    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Delete import instructions")
    public ResponseEntity<Void> deleteImportInstructions(
            @RequestBody @Parameter(description = "List of import instructions ids to delete") List<String> importInstructionIds
    ) {
        importInstructionsService.deleteImportInstructionsByIds(importInstructionIds);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
