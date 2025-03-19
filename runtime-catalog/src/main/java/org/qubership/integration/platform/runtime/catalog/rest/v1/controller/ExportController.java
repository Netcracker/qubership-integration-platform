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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.model.apispec.ApiSpecificationFormat;
import org.qubership.integration.platform.catalog.model.apispec.ApiSpecificationType;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ApiSpecificationExportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@Validated
@RequestMapping(value = "/v1/catalog/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "export-controller", description = "Export Controller")
public class ExportController {

    private final ExportService exportService;
    private final ApiSpecificationExportService apiSpecificationExportService;

    @Autowired
    public ExportController(ExportService exportService, ApiSpecificationExportService apiSpecificationExportService) {
        this.exportService = exportService;
        this.apiSpecificationExportService = apiSpecificationExportService;
    }

    @GetMapping("/chain/{chainId}")
    @Operation(description = "Export chain as a zip archive")
    public ResponseEntity<Object> exportChain(@PathVariable @NotBlank @Parameter(description = "Chain id") String chainId) {
        log.info("Request to export chain with id: {}", chainId);
        Pair<String, byte[]> zip = exportService.exportSingleChain(chainId);
        return asResponse(zip);
    }

    @GetMapping("/chains")
    @Operation(description = "Export multiple chains in a single zip archive")
    public ResponseEntity<Object> exportChains(@RequestParam @NotEmpty @Valid @Parameter(description = "List of chain ids, separated by comma") List<String> chainIds,
                                               @RequestParam(required = false) @Parameter(description = "Whether resulting archive should contain sub-chains called from specified chains") boolean exportWithSubChains) {
        log.info("Request to export chains with IDs: {}", chainIds);
        Pair<String, byte[]> zip = exportService.exportListChains(chainIds, exportWithSubChains);
        return asResponse(zip);
    }

    @GetMapping()
    @Operation(description = "Export all available chains in a single zip archive")
    public ResponseEntity<Object> exportAllChains() {
        log.info("Request to export all chain");
        Pair<String, byte[]> zip = exportService.exportAllChains();
        return asResponse(zip);
    }

    @GetMapping("/api-spec")
    @Operation(description = "Generate API specification")
    public ResponseEntity<Object> exportSpecification(
            @RequestParam(required = false, defaultValue = "") @Parameter(description = "List of deployment ids") List<String> deploymentIds,
            @RequestParam(required = false, defaultValue = "") @Parameter(description = "List of snapshot ids") List<String> snapshotIds,
            @RequestParam(required = false, defaultValue = "") @Parameter(description = "List of chain ids") List<String> chainIds,
            @RequestParam(required = false, defaultValue = "") @Parameter(description = "List of http trigger element ids") List<String> httpTriggerIds,
            @RequestParam(required = false, defaultValue = "true") @Parameter(description = "Whether external routes should be included") boolean externalRoutes,
            @RequestParam(required = false, defaultValue = "OpenAPI") @Parameter(description = "Specification type") ApiSpecificationType specificationType,
            @RequestParam(required = false, defaultValue = "YAML") @Parameter(description = "Specification format") ApiSpecificationFormat format
            ) {
        log.info("Request to export {} specification for {} routes from deployments {}, snapshots {}, and chains {}",
                specificationType, externalRoutes ? "external" : "", deploymentIds, snapshotIds, chainIds);
        Pair<String, byte[]> spec = apiSpecificationExportService.exportApiSpecification(
                deploymentIds, snapshotIds, chainIds, httpTriggerIds, externalRoutes, specificationType, format);
        return asResponse(spec);
    }

    private ResponseEntity<Object> asResponse(Pair<String, byte[]> zip) {
        HttpHeaders header = new HttpHeaders();
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zip.getFirst() + "\"");
        header.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        ByteArrayResource resource = new ByteArrayResource(zip.getSecond());
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(resource.contentLength())
                .body(resource);
    }
}
