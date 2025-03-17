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
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.SpecificationExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/export")
@Tag(name = "specification-export-controller", description = "Specification Export Controller")
public class SpecificationExportController {
    private final SpecificationExportService specificationExportService;

    @Autowired
    public SpecificationExportController(SpecificationExportService specificationExportService) {
        this.specificationExportService = specificationExportService;
    }

    @GetMapping(value = "/specifications", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(description = "Export specifications as a file")
    public ResponseEntity<Object> exportSpecifications(@RequestParam(required = false) @Parameter(description = "List of specification ids separated by comma") List<String> specificationIds,
                                                       @RequestParam(required = false) @Parameter(description = "List of specification group ids separated by comma") String specificationGroupId) {
        Pair<byte[], String> archivePair = specificationExportService.exportSpecifications(specificationIds, specificationGroupId);
        return archivePair == null ?
                ResponseEntity.noContent().build() :
                ExportImportUtils.convertFileToResponse(archivePair.getFirst(), archivePair.getSecond());

    }
}
