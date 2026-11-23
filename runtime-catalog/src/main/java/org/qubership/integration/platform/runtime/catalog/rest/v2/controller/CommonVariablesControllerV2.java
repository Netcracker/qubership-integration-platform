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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.variable.ImportVariablesResult;
import org.qubership.integration.platform.runtime.catalog.service.variables.CommonVariablesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Slf4j
@RestController
@RequestMapping({"/v2/common-variables", "/api/cip/v2/common-variables"})
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "common-variables-controller-v-2", description = "Common Variables Controller V2")
public class CommonVariablesControllerV2 {

    private final CommonVariablesService commonVariablesService;

    @Autowired
    public CommonVariablesControllerV2(CommonVariablesService commonVariablesService) {
        this.commonVariablesService = commonVariablesService;
    }

    @PostMapping(value = "/import")
    @Operation(description = "Import common variable",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                schema = @Schema(type = "object", requiredProperties = {"file"}),
                schemaProperties = {
                    @SchemaProperty(name = "file", schema = @Schema(type = "string", format = "binary", description = "File to import")),
                    @SchemaProperty(name = "variablesNames", array = @ArraySchema(arraySchema = @Schema(description = "Variables names. If set, only these variables will be imported"), uniqueItems = true, schema = @Schema(type = "string")))
                })))
    public ResponseEntity<ImportVariablesResult> importVariablesInternal(
            @RequestParam MultipartFile file,
            @RequestParam(required = false) @Parameter(hidden = true) Set<String> variablesNames
    ) {
        log.info("Request to import common variables");
        ImportVariablesResult importVariablesResult = commonVariablesService.importVariables(file, variablesNames);
        return ResponseEntity.ok(importVariablesResult);
    }
}
