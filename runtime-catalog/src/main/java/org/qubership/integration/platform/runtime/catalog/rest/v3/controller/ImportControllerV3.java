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

package org.qubership.integration.platform.runtime.catalog.rest.v3.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.exception.ChainDifferenceClientException;
import org.qubership.integration.platform.catalog.mapping.EntityDiffResponseMapper;
import org.qubership.integration.platform.catalog.model.dto.chain.EntityDifferenceResponse;
import org.qubership.integration.platform.catalog.service.difference.ChainDifferenceRequest;
import org.qubership.integration.platform.catalog.service.difference.EntityDifferenceResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionStatus;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportCommitResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportPreviewResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportSessionResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v3.mapper.ImportSessionMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v3/import", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "import-controller-v-3", description = "Import Controller V3")
public class ImportControllerV3 {

    public static final String SR_PACKAGE_NAME_HEADER = "X-SR-Package-Name";
    public static final String SR_PACKAGE_VERSION_HEADER = "X-SR-Package-Version";
    public static final String SR_PACKAGE_PART_OF_HEADER = "X-SR-Part-Of";
    private static final String SR_DEFAULT_VALUE = "not implemented";

    private final ObjectMapper objectMapper;
    private final GeneralImportService importService;
    private final ImportSessionMapper importSessionMapper;
    private final EntityDiffResponseMapper entityDiffResponseMapper;

    public ImportControllerV3(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            GeneralImportService importService,
            ImportSessionMapper importSessionMapper,
            EntityDiffResponseMapper entityDiffResponseMapper
    ) {
        this.objectMapper = objectMapper;
        this.importService = importService;
        this.importSessionMapper = importSessionMapper;
        this.entityDiffResponseMapper = entityDiffResponseMapper;
    }

    @PostMapping(value = "/preview")
    @Operation(description = "Get preview on what will be imported from file")
    public ResponseEntity<ImportPreviewResponse> preview(@RequestParam("file") @Parameter(description = "File") MultipartFile file) {
        log.info("Request to preview file: {}", file.getOriginalFilename());

        ImportPreviewResponse response = importService.getImportPreview(file);

        return ResponseEntity.ok(response);
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
            description = "Import chains and related to them services and variables from an archive file")
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportCommitResponse> importFile(
            @RequestParam("file") @Parameter(description = "Archive file") MultipartFile file,
            @RequestParam(name = "importRequest", required = false) @Parameter(description = "Import request object") String importRequest,
            @RequestParam(name = "validateByHash", required = false, defaultValue = "false") @Parameter(description = "Check chain hash during import") boolean validateByHash,
            @RequestHeader(required = false, value = "chain-labels") @Parameter(description = "List of chain labels that should be added on importing chains") List<String> technicalLabels,
            @RequestHeader(required = false, value = SR_PACKAGE_NAME_HEADER) @Parameter(description = "Package name samples repository header") String packageName,
            @RequestHeader(required = false, value = SR_PACKAGE_VERSION_HEADER) @Parameter(description = "Package version samples repository header") String packageVersion,
            @RequestHeader(required = false, value = SR_PACKAGE_PART_OF_HEADER) @Parameter(description = "Package part of samples repository header") String packagePartOf
    ) {
        log.info("Request to import file: {}", file.getOriginalFilename());
        technicalLabels = addSamplesRepoTechnicalLabels(technicalLabels, packagePartOf, packageName, packageVersion);

        ImportRequest importRequestObject;
        try {
            importRequestObject = StringUtils.isNotBlank(importRequest)
                    ? objectMapper.readValue(importRequest, ImportRequest.class)
                    : new ImportRequest();
        } catch (IOException e) {
            throw new RuntimeException("Unable to deserialize importRequest field value: " + importRequest);
        }

        String importId = importService.importFileAsync(file, importRequestObject, new HashSet<>(technicalLabels), validateByHash);

        log.info("File {} imported successfully", file.getOriginalFilename());
        return ResponseEntity.accepted().body(new ImportCommitResponse(importId));
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
            description = "Get import status")
    @GetMapping(value = "/{importId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportSessionResponse> getImportStatus(@PathVariable @Parameter(description = "Import id") String importId) {
        ImportSession importSession = importService.getImportSession(importId);

        if (importSession == null) {
            return ResponseEntity.notFound().build();
        }

        ImportSessionResponse importSessionResponse = importSessionMapper.toImportSessionResponse(importSession);
        HttpStatus responseStatus = HttpStatus.OK;
        if (importSessionResponse.getResult() != null) {
            ImportResult importResult = importSessionResponse.getResult();

            boolean responseHasErrors = importResult.getChains().stream().anyMatch(chainResult -> ImportEntityStatus.ERROR.equals(chainResult.getStatus()))
                    || importResult.getSystems().stream().anyMatch(systemResult -> ImportSystemStatus.ERROR.equals(systemResult.getStatus()))
                    || importResult.getVariables().stream().anyMatch(variableResult -> ImportEntityStatus.ERROR.equals(variableResult.getStatus()))
                    || importResult.getInstructionsResult().stream().anyMatch(instructionResult -> ImportInstructionStatus.ERROR_ON_DELETE.equals(instructionResult.getStatus())
                    || ImportInstructionStatus.ERROR_ON_OVERRIDE.equals(instructionResult.getStatus()));
            if (responseHasErrors) {
                responseStatus = HttpStatus.MULTI_STATUS;
            }
        }

        return ResponseEntity.status(responseStatus).body(importSessionResponse);
    }

    @Operation(description = "Find differences between the chain stored in the QIP database and the imported one",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schemaProperties = {
                            @SchemaProperty(name = "file", schema = @Schema(
                                    description = "Archive file",
                                    requiredMode = Schema.RequiredMode.REQUIRED,
                                    example = "(binary)"
                            )),
                            @SchemaProperty(name = "diffRequest", schema = @Schema(
                                    description = "Chain difference request object",
                                    requiredMode = Schema.RequiredMode.REQUIRED,
                                    requiredProperties = {"leftChainId", "rightChainId"},
                                    example = "{\"leftChainId\":\"string\",\"leftSnapshotId\":\"string\",\"rightChainId\":\"string\"}"
                            ))
                    }
            )))
    @PostMapping(value = "/chains/diff", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EntityDifferenceResponse> difference(
            @RequestParam("file") MultipartFile file,
            @RequestParam("diffRequest") @Parameter(hidden = true) String diffRequest
    ) {
        ChainDifferenceRequest chainDiffRequest;
        try {
            chainDiffRequest = objectMapper.readValue(diffRequest, ChainDifferenceRequest.class);
        } catch (IOException e) {
            throw new RuntimeException("Unable to deserialize diffRequest field value: " + diffRequest);
        }

        List<String> validationMessages = new ArrayList<>();
        if (chainDiffRequest.getLeftChainId() == null) {
            validationMessages.add("The leftChainId must not be null");
        }
        if (chainDiffRequest.getRightChainId() == null) {
            validationMessages.add("The rightChainId must not be null");
        }
        if (!validationMessages.isEmpty()) {
            throw new ChainDifferenceClientException("Diff request validation failed: " + validationMessages);
        }

        EntityDifferenceResult diffResult = importService.compareImportEntities(file, chainDiffRequest);
        return ResponseEntity.ok(entityDiffResponseMapper.asResponse(diffResult));
    }

    public static List<String> addSamplesRepoTechnicalLabels(List<String> technicalLabels, String... labels) {
        List<String> result = technicalLabels == null ? new ArrayList<>() : new ArrayList<>(technicalLabels);

        for (String label : labels) {
            if (StringUtils.isNotBlank(label) && !SR_DEFAULT_VALUE.equals(label)) {
                result.add(label);
            }
        }
        return result;
    }

}
