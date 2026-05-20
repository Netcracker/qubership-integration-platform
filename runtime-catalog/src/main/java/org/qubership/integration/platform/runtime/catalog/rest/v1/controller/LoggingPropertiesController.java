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
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.logging.properties.ChainLoggingPropertiesSet;
import org.qubership.integration.platform.runtime.catalog.service.ChainRuntimePropertiesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping(value = "/v1/chains", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "logging-properties-controller", description = "Logging Properties Controller")
public class LoggingPropertiesController {
    private final ChainRuntimePropertiesService propertiesService;

    @Autowired
    public LoggingPropertiesController(ChainRuntimePropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    @GetMapping("/{chainId}/properties/logging")
    @Operation(description = "Get logging deployment properties for specified chain")
    public ResponseEntity<ChainLoggingPropertiesSet> findByChainId(@PathVariable @Parameter(description = "Chain id") String chainId) {
        ChainLoggingPropertiesSet runtimeProperties = propertiesService.getRuntimeProperties(chainId);
        return ResponseEntity.ok(runtimeProperties);
    }

    @PostMapping("/{chainId}/properties/logging")
    @Operation(description = "Save logging deployment properties for specified chain")
    public ResponseEntity<Void> saveProperties(@PathVariable @Parameter(description = "Chain id") String chainId,
                                               @RequestBody @Parameter(description = "Chain logging deployment properties") DeploymentRuntimeProperties request) {
        propertiesService.saveRuntimeProperties(chainId, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{chainId}/properties/logging")
    @Operation(description = "Remove custom logging properties for chain (make default)")
    public ResponseEntity<Void> deleteProperties(@PathVariable @Parameter(description = "Chain id") String chainId) {
        propertiesService.deleteCustomRuntimeProperties(chainId);
        return ResponseEntity.noContent().build();
    }
}
