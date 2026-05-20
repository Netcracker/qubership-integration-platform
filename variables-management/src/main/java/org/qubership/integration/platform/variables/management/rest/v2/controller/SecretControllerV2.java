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
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.service.SecretService;
import org.qubership.integration.platform.variables.management.util.ExportImportUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


@Slf4j
@RestController
@RequestMapping("/v2/secret")
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "secret-controller-v-2", description = "Secret Controller V2")
public class SecretControllerV2 {

    private final SecretService secretService;

    public SecretControllerV2(SecretService secretService) {
        this.secretService = secretService;
    }

    @Operation(description = "Create new secret")
    @PostMapping("/{secretName}")
    public ResponseEntity<Void> createSecret(
            @PathVariable @Pattern(regexp = "^[a-z]+[-a-z0-9]*$", message = "does not match \"{regexp}\"") @Parameter(description = "Name of secret") String secretName
    ) {
        log.info("Request to create secret {}", secretName);
        secretService.createSecuredVariablesSecret(secretName);
        return ResponseEntity.ok().build();
    }

    @Operation(description = "Get helm template for creating secret on kubernetes as a file")
    @GetMapping("/template/{secretName}")
    public ResponseEntity<Object> downloadSecretHelmChart(@PathVariable @Parameter(description = "Name of secret") String secretName) {
        String secretYaml = secretService.getSecretTemplate(secretName);
        return ExportImportUtils.bytesAsResponse(secretYaml.getBytes(), secretName + ".yaml");
    }
}
