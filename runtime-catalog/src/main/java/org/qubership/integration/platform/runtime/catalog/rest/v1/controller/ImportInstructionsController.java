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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionResult;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
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

    private final ImportInstructionsService importInstructionsService;

    @Autowired
    public ImportInstructionsController(ImportInstructionsService importInstructionsService) {
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
}
