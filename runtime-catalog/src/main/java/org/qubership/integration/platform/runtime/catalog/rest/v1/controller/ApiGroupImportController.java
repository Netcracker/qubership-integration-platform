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
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.ImportSpecificationDTO;
import org.qubership.integration.platform.runtime.catalog.service.ApiGroupService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.SpecificationImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/specificationGroups/import")
@Tag(name = "specification-group-import-controller", description = "Specification Group Import Controller")
public class ApiGroupImportController {
    private final ApiGroupService apiGroupService;
    private final SpecificationImportService specificationImportService;

    @Autowired
    public ApiGroupImportController(
            ApiGroupService apiGroupService,
            SpecificationImportService specificationImportService
    ) {
        this.apiGroupService = apiGroupService;
        this.specificationImportService = specificationImportService;
    }

    @PostMapping
    @Operation(operationId = "importSpecificationGroup", description = "Create API group from a file")
    public ResponseEntity<ImportSpecificationDTO> importApiGroup(
            @RequestParam @Parameter(description = "Service id") String systemId,
            @RequestParam("name") @Parameter(description = "Specification group name") String specificationName,
            @RequestParam(required = false) @Parameter(description = "Specification protocol") String protocol,
            @RequestParam @Parameter(description = "Array of specification files") MultipartFile[] files
    ) {
        ApiGroup apiGroup = apiGroupService.createAndSaveSpecificationGroup(
                systemId, specificationName, protocol, files);
        String importId = specificationImportService.importSpecification(apiGroup.getId(), files);
        ImportSpecificationDTO responseDTO = new ImportSpecificationDTO(importId, false);
        responseDTO.setSpecificationGroupId(apiGroup.getId());

        return ResponseEntity.accepted().location(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{importId}")
                .buildAndExpand(responseDTO.getId())
                .toUri()).body(responseDTO);
    }
}
