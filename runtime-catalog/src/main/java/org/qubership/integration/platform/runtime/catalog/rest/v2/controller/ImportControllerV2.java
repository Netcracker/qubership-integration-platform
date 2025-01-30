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

package org.qubership.integration.platform.runtime.catalog.rest.v2.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.exportimport.chain.ImportAsyncAcknowledge;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.exportimport.chain.ImportAsyncStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v2.mapper.ImportChainAsyncMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v3.controller.ImportControllerV3;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportSessionService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportV2RedirectPathResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.*;

/**
 * @deprecated 23.4 use {@link ImportControllerV3} instead
 */
@Slf4j
@RestController
@RequestMapping(value = ImportControllerV2.REQUEST_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Deprecated(since = "2023.4")
@Tag(name = "import-controller-v-2", description = "Import Controller V2")
public class ImportControllerV2 {
    protected static final String REQUEST_PATH = "/v2/import";

    private final ImportService importService;
    private final ImportSessionService importProgressService;
    private final ObjectMapper objectMapper;
    private final ImportChainAsyncMapper importChainAsyncMapper;
    private final ImportV2RedirectPathResolver importV2RedirectPathResolver;

    private static final String STATUS_PATH = "/status";

    @Autowired
    public ImportControllerV2(ImportService importService,
                              ImportSessionService importProgressService,
                              @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
                              ImportChainAsyncMapper importChainAsyncMapper,
                              ImportV2RedirectPathResolver importV2RedirectPathResolver) {
        this.importService = importService;
        this.importProgressService = importProgressService;
        this.objectMapper = objectMapper;
        this.importChainAsyncMapper = importChainAsyncMapper;
        this.importV2RedirectPathResolver = importV2RedirectPathResolver;
    }

    @PostMapping()
    @Operation(description = "Import chains from file asynchronously")
    public ResponseEntity<ImportAsyncAcknowledge> importFileAsync(@RequestParam("file") @Parameter(description = "File") MultipartFile file,
                                                                  @RequestParam(required = false) @Parameter(description = "Import requests") String chainCommitRequests,
                                                                  @RequestHeader Map<String, String> headers,
                                                                  @RequestHeader(required = false, value = "chain-labels") @Parameter(description = "List of labels to add on chains") List<String> technicalLabels,
                                                                  @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_NAME_HEADER) @Parameter(description = "Package name samples repository header") String packageName,
                                                                  @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_VERSION_HEADER) @Parameter(description = "Package version samples repository header") String packageVersion,
                                                                  @RequestHeader(required = false, value = ImportControllerV3.SR_PACKAGE_PART_OF_HEADER) @Parameter(description = "Package part of samples repository header") String packagePartOf) {
        log.info("Request v2 to import file: {}", file.getOriginalFilename());
        technicalLabels = ImportControllerV3.addSamplesRepoTechnicalLabels(technicalLabels, packagePartOf, packageName, packageVersion);
        List<ChainCommitRequest> chainCommitRequestsList = Collections.emptyList();

        try {
            if (chainCommitRequests != null) {
                chainCommitRequestsList = Arrays.asList(objectMapper.readValue(chainCommitRequests, ChainCommitRequest[].class));
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot parse chainCommitRequests parameter", e);
        }
        String importId = importService.importFileAsync(file, chainCommitRequestsList, new HashSet<>(technicalLabels));
        URI href = getHrefStatusLocation(importId, headers);

        return ResponseEntity.accepted().location(href).header(HttpHeaders.RETRY_AFTER, "60")
                .body(new ImportAsyncAcknowledge(importId, href.toString()));
    }

    @GetMapping(value = "/{importId}", produces = "application/json")
    @Operation(description = "Get import result")
    public ResponseEntity<List<ImportChainResult>> getImportAsyncResult(@PathVariable @Parameter(description = "Import id") String importId) {
        List<ImportChainResult> result = importService.getImportAsyncResult(importId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        HttpStatus responseCode = result.stream().anyMatch(el -> el.getStatus().equals(ImportEntityStatus.ERROR))
                ? HttpStatus.MULTI_STATUS : HttpStatus.OK;
        return ResponseEntity.status(responseCode).body(result);
    }

    @GetMapping(value = {STATUS_PATH + "/{importId}", "/preview/{importId}/status"}, produces = "application/json")
    @Operation(description = "Get import status (progress)")
    public ResponseEntity<ImportAsyncStatus> getImportAsyncStatus(@PathVariable @Parameter(description = "Import id") String importId,
                                                                  @RequestHeader Map<String, String> headers) {
        ImportSession importSession = importProgressService.getImportSession(importId);
        if (importSession == null) {
            return ResponseEntity.notFound().build();
        }
        ImportAsyncStatus importStatus = importChainAsyncMapper.asImportStatus(importSession);
        if (importSession.isDone()) {
            URI href = getHrefResultLocation(importId, headers);
            importStatus.setHref(href.toString());
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(href).body(importStatus);
        }
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.RETRY_AFTER, "60").body(importStatus);
    }

    private URI getHrefResultLocation(String importId, Map<String, String> headers) {
        return concatenateUri(importV2RedirectPathResolver.resolve(headers, REQUEST_PATH), importId);
    }

    private URI getHrefStatusLocation(String importId, Map<String, String> headers) {
        return concatenateUri(importV2RedirectPathResolver.resolve(headers, REQUEST_PATH), STATUS_PATH, importId);
    }

    private URI concatenateUri(URI uri, String... paths) {
        StringBuilder builder = new StringBuilder(uri.toString());
        for (String path : paths) {
            builder.append("/").append(path);
        }
        return URI.create(builder.toString()).normalize();
    }
}
