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
import org.qubership.integration.platform.catalog.exception.SpecificationImportWarningException;
import org.qubership.integration.platform.catalog.service.exportimport.SpecificationImportService;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.ImportSpecificationDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/import")
@Tag(name = "specification-import-controller", description = "Specification Import Controller")
public class SpecificationImportController {
    private final SpecificationImportService specificationImportService;

    @Autowired
    public SpecificationImportController(SpecificationImportService specificationImportService) {
        this.specificationImportService = specificationImportService;
    }

    @PostMapping
    @Operation(description = "Import single specification from a file")
    public ResponseEntity<ImportSpecificationDTO> importSpecification(@RequestParam @Parameter(description = "Specification group id") String specificationGroupId,
                                                                      @RequestParam @Parameter(description = "Array of specification files") MultipartFile[] files) {
        String importId = specificationImportService.importSpecification(specificationGroupId, files);
        ImportSpecificationDTO responseDTO = new ImportSpecificationDTO(importId, false);
        responseDTO.setSpecificationGroupId(specificationGroupId);
        return ResponseEntity.accepted().location(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{importId}")
                .buildAndExpand(responseDTO.getId())
                .toUri()).body(responseDTO);
    }

    @GetMapping(value = "/{importId}", produces = "application/json")
    @Operation(description = "Get specification import result")
    public ResponseEntity<ImportSpecificationDTO> getImport(@PathVariable @Parameter(description = "Import id") String importId) {
        ImportSpecificationDTO responseDTO = new ImportSpecificationDTO();
        responseDTO.setId(importId);
        try {
            responseDTO.setDone(specificationImportService.importSessionIsDone(importId));
        } catch (SpecificationImportWarningException e) {
            responseDTO.setDone(true);
            responseDTO.setWarningMessage(e.getMessage());
        }
        return ResponseEntity.ok(responseDTO);
    }
}
