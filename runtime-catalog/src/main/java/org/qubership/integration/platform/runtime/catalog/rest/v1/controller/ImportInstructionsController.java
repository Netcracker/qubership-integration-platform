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
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsDTO;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionDTO;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionResult;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.GeneralInstructionsMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.instructions.DeleteInstructionsRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.instructions.ImportInstructionRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.instructions.ImportInstructionsSearchRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ImportInstructionRequestMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/import-instructions")
@CrossOrigin(origins = "*")
@Tag(name = "import-instructions-controller", description = "Import Instructions Controller")
public class ImportInstructionsController {

    private final ImportInstructionRequestMapper importInstructionRequestMapper;
    private final GeneralInstructionsMapper generalInstructionsMapper;
    private final ImportInstructionsService importInstructionsService;

    @Autowired
    public ImportInstructionsController(ImportInstructionRequestMapper importInstructionRequestMapper, GeneralInstructionsMapper generalInstructionsMapper, ImportInstructionsService importInstructionsService) {
        this.importInstructionRequestMapper = importInstructionRequestMapper;
        this.generalInstructionsMapper = generalInstructionsMapper;
        this.importInstructionsService = importInstructionsService;
    }

    @GetMapping(value = "/export")
    @Operation(description = "Export import instructions configuration")
    public ResponseEntity<Object> exportImportInstructionsConfig() {
        Pair<String, byte[]> importInstructions = importInstructionsService.exportImportInstructions();
        HttpHeaders header = new HttpHeaders();
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + importInstructions.getLeft() + "\"");
        header.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        ByteArrayResource resource = new ByteArrayResource(importInstructions.getRight());
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(resource.contentLength())
                .body(resource);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
            description = "Upload import instructions configuration from file")
    public ResponseEntity<List<ImportInstructionResult>> uploadImportInstructionsConfig(
            @RequestParam("file") @Parameter(description = "Yaml file") MultipartFile file,
            @RequestHeader(required = false, value = "labels") @Parameter(description = "List of labels that should be added on uploaded instructions") Set<String> labels
    ) {
        log.info("Request to upload import instructions config from file {}", file.getOriginalFilename());

        return ResponseEntity.ok(importInstructionsService.uploadImportInstructionsConfig(file, labels));
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
