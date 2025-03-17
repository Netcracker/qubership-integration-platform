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
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.rest.v1.dto.StringResponse;
import org.qubership.integration.platform.variables.management.service.SecuredVariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/v1/secured-variables")
@CrossOrigin(origins = "*")
@Tag(name = "secured-variable-controller", description = "Secured Variable Controller")
public class SecuredVariableController {

    private final SecuredVariableService securedVariableService;

    @Autowired
    public SecuredVariableController(SecuredVariableService securedVariableService) {
        this.securedVariableService = securedVariableService;
    }

    @Operation(description = "Get all secured variables names")
    @GetMapping()
    public ResponseEntity<Set<String>> getVariables() {
        if (log.isDebugEnabled()) {
            log.debug("Request to get secured variables");
        }
        return ResponseEntity.ok(securedVariableService.getVariablesForDefaultSecret(true));
    }

    @Operation(description = "Add new secured variable(s)")
    @PostMapping()
    public ResponseEntity<Set<String>> addVariables(@RequestBody @Parameter(description = "Variables map of <key, value> to add") Map<String, String> variables) {
        log.info("Request to add secured variables");
        Set<String> addedVariables = securedVariableService.addVariablesToDefaultSecret(variables);
        return ResponseEntity.ok(addedVariables);
    }

    @Operation(description = "Update or add single secured variable")
    @PatchMapping("/{securedVariableName}")
    public ResponseEntity<StringResponse> updateVariable(@PathVariable @Parameter(description = "Secured variable name") String securedVariableName,
                                                         @RequestBody(required = false) @Parameter(description = "Secured variable value") String value) {
        log.info("Request to update secured variable {}", securedVariableName);
        String updatedVariable = securedVariableService.updateVariableInDefaultSecret(securedVariableName, value);
        return ResponseEntity.ok(new StringResponse(updatedVariable));
    }

    @Operation(description = "Delete set of secured variables")
    @DeleteMapping()
    public ResponseEntity<Void> deleteVariables(@RequestParam @Parameter(description = "Set of names of secured variables") Set<String> variablesNames) {
        log.info("Request to delete secured variables");
        securedVariableService.deleteVariablesFromDefaultSecret(variablesNames);
        return ResponseEntity.noContent().build();
    }

    @Operation(description = "Import secured variables from a file")
    @PostMapping(value = "/import")
    public ResponseEntity<Set<String>> importVariables(@RequestParam("file") @Parameter(description = "File to import") MultipartFile file) {
        log.info("Request to import secured variables");
        Set<String> result = securedVariableService.importVariablesRequest(file);
        return result.isEmpty()
                ? ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
                : ResponseEntity.ok().body(result);
    }
}
