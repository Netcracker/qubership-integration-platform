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
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v3.controller.ImportControllerV3;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.SystemExportImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1")
@Tag(name = "system-export-import-controller", description = "System Export Import Controller")
public class SystemExportImportController {
    private final SystemExportImportService systemExportImportService;

    @Autowired
    public SystemExportImportController(SystemExportImportService systemExportImportService) {
        this.systemExportImportService = systemExportImportService;
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST}, value = "/export/system",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(description = "Export services as an archive")
    public ResponseEntity<Object> exportSystems(@RequestParam(required = false) @Parameter(description = "List of service ids, separated by comma") List<String> systemIds,
                                                @RequestParam(required = false) @Parameter(description = "If specified, only these specifications will be exported") List<String> usedSystemModelIds) {
        byte[] zip = systemExportImportService.exportSystemsRequest(systemIds, usedSystemModelIds);
        if (zip == null) {
            return ResponseEntity.noContent().build();
        }

        return ExportImportUtils.convertFileToResponse(zip, ExportImportUtils.generateArchiveExportName());
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
    description = "Import service from a file")
    @PostMapping(value = "/import/system")
    public ResponseEntity<List<ImportSystemResult>> importSystems(@RequestParam("file") @Parameter(description = "File") MultipartFile file,
                                                                  @RequestParam(required = false) @Parameter(description = "List of service ids, separated by comma") List<String> systemIds,
                                                                  @RequestParam(required = false) @Parameter(description = "If specified, all environments with specified label will be set as active") String deployLabel,
                                                                  @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_NAME_HEADER) @Parameter(description = "Package name samples repository header") String packageName,
                                                                  @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_VERSION_HEADER) @Parameter(description = "Package version samples repository header") String packageVersion,
                                                                  @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_PART_OF_HEADER) @Parameter(description = "Package part of samples repository header") String packagePartOf) {
        List<String> technicalLabels = ImportControllerV3.addSamplesRepoTechnicalLabels(null, packagePartOf, packageName, packageVersion);
        List<ImportSystemResult> result = systemExportImportService.importSystemRequest(file, systemIds, deployLabel, new HashSet<>(technicalLabels));
        if (result.isEmpty()) {
            return ResponseEntity.noContent().build();
        } else {
            HttpStatus responseCode = result.stream().anyMatch(dto -> dto.getStatus().equals(ImportSystemStatus.ERROR))
                    ? HttpStatus.MULTI_STATUS
                    : HttpStatus.OK;
            return ResponseEntity.status(responseCode).body(result);
        }
    }

    @PostMapping(value = "/import/systemPreview")
    @Operation(description = "Get preview on what will be imported from file")
    public ResponseEntity<List<ImportSystemResult>> getSystemsImportPreview(@RequestParam("file") @Parameter(description = "File") MultipartFile file) {
        List<ImportSystemResult> result = systemExportImportService.getSystemsImportPreviewRequest(file);
        return result.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok().body(result);
    }
}

