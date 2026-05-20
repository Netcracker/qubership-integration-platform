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

package org.qubership.integration.platform.variables.management.rest.v2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.SecretErrorResponse;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.SecretResponse;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.SecuredVariablesRequest;
import org.qubership.integration.platform.variables.management.rest.v2.mapper.SecretResponseMapper;
import org.qubership.integration.platform.variables.management.service.SecuredVariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/v2/secured-variables")
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "secured-variable-controller-v-2", description = "Secured Variable Controller V2")
public class SecuredVariableControllerV2 {

    private final SecuredVariableService securedVariableService;
    private final SecretResponseMapper secretResponseMapper;

    @Autowired
    public SecuredVariableControllerV2(SecuredVariableService securedVariableService, SecretResponseMapper secretResponseMapper) {
        this.securedVariableService = securedVariableService;
        this.secretResponseMapper = secretResponseMapper;
    }

    @Operation(description = "Get all secured variables names from all secrets")
    @GetMapping()
    public ResponseEntity<List<SecretResponse>> getVariables() {
        if (log.isDebugEnabled()) {
            log.debug("Request to get secured variables from all secrets");
        }

        Map<String, Set<String>> secrets = securedVariableService.getAllSecretsVariablesNames();
        return ResponseEntity.ok(secretResponseMapper.asResponse(secrets));
    }

    @Operation(description = "Get all secured variables names from specified secret")
    @GetMapping("/{secretName}")
    public ResponseEntity<Set<String>> getVariablesForSecret(@PathVariable @Parameter(description = "Name of secret") String secretName) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get secured variables from secret {}", secretName);
        }

        return ResponseEntity.ok(securedVariableService.getVariablesForSecret(secretName, true));
    }

    @Operation(description = "Add new secured variables")
    @PostMapping()
    public ResponseEntity<List<SecretResponse>> addVariables(@Valid @RequestBody SecuredVariablesRequest request) {
        log.info("Request to add secured variables to secret {}", request.getSecretName());

        Map<String, Set<String>> secrets = securedVariableService.addVariables(request.getSecretName(), request.getVariables());
        return ResponseEntity.ok(secretResponseMapper.asResponse(secrets));
    }

    @Operation(description = "Update secured variables")
    @PatchMapping()
    public ResponseEntity<SecretResponse> updateVariable(@RequestBody SecuredVariablesRequest updateRequest) {
        log.info("Request to update secured variables {}", updateRequest.getVariables().keySet());

        Pair<String, Set<String>> updatedVariables = securedVariableService.updateVariables(
                updateRequest.getSecretName(),
                updateRequest.getVariables()
        );
        return ResponseEntity.ok(secretResponseMapper.asResponse(updatedVariables));
    }

    @Operation(description = "Delete secured variables from specified secret")
    @DeleteMapping("/{secretName}")
    public ResponseEntity<Void> deleteVariablesFromSecret(
            @PathVariable @Parameter(description = "Name of secret") String secretName,
            @RequestParam @Parameter(description = "Set of variables names for removal") Set<String> variablesNames
    ) {
        log.info("Request to delete secured variables from secret {}", secretName);

        securedVariableService.deleteVariables(secretName, variablesNames);
        return ResponseEntity.noContent().build();
    }

    @Operation(description = "Delete secured variables from multiple specified secrets")
    @DeleteMapping()
    public ResponseEntity<?> deleteVariables(@RequestBody @Parameter(description = "Request map of <Secret name, Set<Variable name>>") Map<String, Set<String>> secretsVariables) {
        log.info("Request to delete secured variables");

        List<SecretErrorResponse> errorResponses = securedVariableService.deleteVariablesForMultipleSecrets(secretsVariables);
        return errorResponses.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.MULTI_STATUS).body(errorResponses);
    }
}
