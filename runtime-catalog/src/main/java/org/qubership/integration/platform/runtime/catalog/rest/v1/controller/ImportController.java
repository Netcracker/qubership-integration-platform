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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportPreviewDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.controller.ImportControllerV3;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * @deprecated 23.4 use {@link ImportControllerV3} instead
 */
@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/import", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Deprecated(since = "2023.4")
@Tag(name = "import-controller", description = "Import Controller")
public class ImportController {

    private final ImportService importService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ImportController(
            ImportService importService,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper
    ) {
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    @PostMapping()
    @Operation(description = "Import chains from file")
    public ResponseEntity<ImportDTO> importFile(@RequestParam("file") @Parameter(description = "File") MultipartFile file,
                                                @RequestParam(required = false) @Parameter(description = "Import requests") String chainCommitRequests,
                                                @RequestHeader(required = false, value = "chain-labels") @Parameter(description = "List of labels to add on chains") List<String> technicalLabels,
                                                @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_NAME_HEADER) @Parameter(description = "Package name samples repository header") String packageName,
                                                @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_VERSION_HEADER) @Parameter(description = "Package version samples repository header") String packageVersion,
                                                @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_PART_OF_HEADER) @Parameter(description = "Package part of samples repository header") String packagePartOf) {
        log.info("Request to import file: {}", file.getOriginalFilename());
        technicalLabels = ImportControllerV3.addSamplesRepoTechnicalLabels(technicalLabels, packagePartOf, packageName, packageVersion);
        List<ChainCommitRequest> chainCommitRequestsList = Collections.emptyList();

        try {
            if (chainCommitRequests != null) {
                chainCommitRequestsList = Arrays.asList(objectMapper.readValue(chainCommitRequests,
                        ChainCommitRequest[].class));
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot parse chainCommitRequests parameter", e);
        }

        ImportDTO importDTO = importService.importFile(file, chainCommitRequestsList, new HashSet<>(technicalLabels));

        HttpStatus responseCode = (importDTO != null && importDTO.getChains() != null &&
                importDTO.getChains().stream().anyMatch(dto -> dto.getStatus().equals(ImportEntityStatus.ERROR))) ?
                HttpStatus.MULTI_STATUS : HttpStatus.OK;
        log.info("File {} imported successfully", file.getOriginalFilename());
        return ResponseEntity.status(responseCode).body(importDTO);
    }

    @PostMapping("/preview")
    @Operation(description = "Get preview on what will be imported from file")
    public ResponseEntity<ImportPreviewDTO> importFileAsPreview(@RequestParam("file") @Parameter(description = "File") MultipartFile file) {
        log.info("Request to preview file: {}", file.getOriginalFilename());
        ImportPreviewDTO importDTO = importService.importFileAsPreview(file);

        return ResponseEntity.ok(importDTO);
    }
}
