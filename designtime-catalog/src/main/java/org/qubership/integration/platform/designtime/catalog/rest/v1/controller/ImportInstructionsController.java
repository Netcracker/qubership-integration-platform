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

package org.qubership.integration.platform.designtime.catalog.rest.v1.controller;

import org.qubership.integration.platform.catalog.mapping.exportimport.instructions.GeneralInstructionsMapper;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.GeneralImportInstructionsDTO;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionDTO;
import org.qubership.integration.platform.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.exportimport.instructions.DeleteInstructionsRequest;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.exportimport.instructions.ImportInstructionRequest;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.exportimport.instructions.ImportInstructionsSearchRequestDTO;
import org.qubership.integration.platform.designtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.designtime.catalog.rest.v1.mapping.ImportInstructionRequestMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v1/import-instructions", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "import-instructions-controller", description = "Import Instructions Controller")
public class ImportInstructionsController {

    private final ImportInstructionRequestMapper importInstructionRequestMapper;
    private final GeneralInstructionsMapper generalInstructionsMapper;
    private final ImportInstructionsService importInstructionsService;

    public ImportInstructionsController(
            ImportInstructionRequestMapper importInstructionRequestMapper,
            GeneralInstructionsMapper generalInstructionsMapper,
            ImportInstructionsService importInstructionsService
    ) {
        this.importInstructionRequestMapper = importInstructionRequestMapper;
        this.generalInstructionsMapper = generalInstructionsMapper;
        this.importInstructionsService = importInstructionsService;
    }

    @GetMapping
    @Operation(description = "Get all import instructions")
    public ResponseEntity<GeneralImportInstructionsDTO> getImportInstructions() {
        if (log.isDebugEnabled()) {
            log.debug("Request to get all import instructions");
        }

        List<ImportInstruction> importInstructions = importInstructionsService.getImportInstructions();
        GeneralImportInstructionsDTO generalImportInstructions = generalInstructionsMapper.asDTO(importInstructions);
        return ResponseEntity.ok(generalImportInstructions);
    }

    @PostMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Search for import instructions")
    public ResponseEntity<GeneralImportInstructionsDTO> searchImportInstructions(
            @RequestBody @Parameter(description = "Import instructions search request object") ImportInstructionsSearchRequestDTO importInstructionsSearchRequestDTO
    ) {
        GeneralImportInstructionsDTO instructionsDTO = generalInstructionsMapper.asDTO(
                importInstructionsService.searchImportInstructions(importInstructionsSearchRequestDTO.getSearchCondition())
        );
        return ResponseEntity.ok(instructionsDTO);
    }

    @PostMapping(value = "/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Filter import instructions")
    public ResponseEntity<GeneralImportInstructionsDTO> filterImportInstructions(
            @RequestBody @Parameter(description = "Import instructions filter request object") List<FilterRequestDTO> filterRequestDTOS
    ) {
        GeneralImportInstructionsDTO instructionsDTO = generalInstructionsMapper.asDTO(
                importInstructionsService.getImportInstructions(filterRequestDTOS)
        );
        return ResponseEntity.ok(instructionsDTO);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Create new import instruction")
    public ResponseEntity<ImportInstructionDTO> addImportInstruction(
            @RequestBody @Valid @Parameter(description = "Create import instructions request object") ImportInstructionRequest importInstructionRequest
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to add new import instruction: {}", importInstructionRequest);
        }

        ImportInstruction importInstruction = importInstructionRequestMapper.toEntity(importInstructionRequest);
        importInstruction = importInstructionsService.addImportInstruction(importInstruction);
        return ResponseEntity.ok(generalInstructionsMapper.entityToDTO(importInstruction));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Update existing import instruction")
    public ResponseEntity<ImportInstructionDTO> updateImportInstructionsConfig(
            @RequestBody @Valid @Parameter(description = "Update import instruction request object") ImportInstructionRequest importInstructionRequest
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to update existing import instruction: {}", importInstructionRequest);
        }

        ImportInstruction importInstruction = importInstructionRequestMapper.toEntity(importInstructionRequest);
        importInstruction = importInstructionsService.updateImportInstruction(importInstruction);
        return ResponseEntity.ok(generalInstructionsMapper.entityToDTO(importInstruction));
    }

    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Delete import instructions")
    public ResponseEntity<Void> deleteImportInstructions(
            @RequestBody @Parameter(description = "Delete import instructions request object") DeleteInstructionsRequest deleteInstructionsRequest
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to delete import instructions: {}", deleteInstructionsRequest);
        }

        importInstructionsService.deleteImportInstructionsById(deleteInstructionsRequest);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
