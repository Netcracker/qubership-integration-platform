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

package org.qubership.integration.platform.variables.management.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.rest.v1.dto.StringResponse;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.ImportVariableDTO;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.ImportVariablePreview;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.VariablesFileResponse;
import org.qubership.integration.platform.variables.management.rest.v1.mapper.CommonVariablesMapper;
import org.qubership.integration.platform.variables.management.service.CommonVariablesService;
import org.qubership.integration.platform.variables.management.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.variables.management.validation.EntityValidator.ENTITY_NAME_REGEXP;

@Slf4j
@RestController
@RequestMapping(value = "/v1/common-variables")
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "common-variables-controller", description = "Common Variables Controller")
public class CommonVariablesController {
    private final CommonVariablesService commonVariablesService;
    private final CommonVariablesMapper commonVariablesMapper;

    @Autowired
    public CommonVariablesController(CommonVariablesService commonVariablesService,
                                     CommonVariablesMapper commonVariablesMapper) {
        this.commonVariablesService = commonVariablesService;
        this.commonVariablesMapper = commonVariablesMapper;
    }

    @Operation(description = "Get all common variables")
    @GetMapping()
    public ResponseEntity<Map<String, String>> getVariables() {
        if (log.isDebugEnabled()) {
            log.debug("Request to get common variables");
        }
        return ResponseEntity.ok(commonVariablesService.getVariables());
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
    description = "Add new common variable(s)")
    @PostMapping()
    public ResponseEntity<List<String>> addVariables(
            @Valid
            @RequestBody
            @Parameter(description = "New common variables as a map of <key, value>")
            Map<@Pattern(regexp = ENTITY_NAME_REGEXP, message = "does not match \"{regexp}\"") String, String> variables
    ) {
        log.info("Request to add common variable");
        List<String> body = commonVariablesMapper.importAsNames(
                commonVariablesService.addVariables(variables, false));
        return ResponseEntity.ok(body);
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
    description = "Update or add single common variable")
    @PatchMapping("/{name}")
    public ResponseEntity<StringResponse> updateVariable(
        @PathVariable
        @Parameter(description = "Name of the variable")
        String name,
        @RequestBody(required = false)
        @Parameter(description = "New value of the parameter")
        String value
    ) {
        log.info("Request to update common variable {}", name);
        String commonVariable = commonVariablesService.addVariable(name, value);
        return ResponseEntity.ok(new StringResponse(commonVariable));
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
    description = "Delete set of variables")
    @DeleteMapping()
    public ResponseEntity<Void> deleteVariables(@RequestParam @Parameter(description = "Set of variables to remove") List<String> variablesNames) {
        log.info("Request to delete common variables");
        commonVariablesService.deleteVariables(variablesNames);
        return ResponseEntity.noContent().build();
    }

    @Operation(description = "Export common variables as a file")
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Object> exportVariables(
            @Valid
            @RequestParam(required = false)
            @Parameter(description = "List of variables names to export")
            List<@Pattern(regexp = ENTITY_NAME_REGEXP, message = "does not match \"{regexp}\"") String> variablesNames,
            @RequestParam(defaultValue = "false")
            @Parameter(description = "Whether response should be in archive")
            boolean asArchive
    ) {
        log.info("Request to export common variables");
        VariablesFileResponse response = commonVariablesService.exportVariables(variablesNames, asArchive);
        if (response == null || response.getContent() == null) {
            return ResponseEntity.noContent().build();
        }

        return ExportImportUtils.bytesAsResponse(response.getContent(), response.getFileName());
    }

    @Deprecated(since = "24.4")
    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
    description = "Import common variables from file")
    @PostMapping(value = "/import")
    public ResponseEntity<List<ImportVariableDTO>> importVariables(
            @RequestParam @Parameter(description = "File to import") MultipartFile file,
            @Valid
            @RequestParam(required = false)
            @Parameter(description = "Variables names. If set, only these variables will be imported")
            Set<@Pattern(regexp = ENTITY_NAME_REGEXP, message = "does not match \"{regexp}\"") String> variablesNames
    ) {
        log.info("Request to import common variables");
        List<ImportVariableDTO> importedVariables = commonVariablesService.importVariables(file, variablesNames).getVariables();
        return CollectionUtils.isEmpty(importedVariables)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok().body(importedVariables);
    }

    @Operation(description = "Get common variables from file without saving")
    @PostMapping(value = "/preview")
    public ResponseEntity<List<ImportVariablePreview>> importPreview(@RequestParam @Parameter(description = "File to import") MultipartFile file) {
        log.debug("Request to import common variables preview");
        return ResponseEntity.ok(commonVariablesService.importVariablePreview(file));
    }
}
