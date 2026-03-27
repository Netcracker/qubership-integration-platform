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
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.dto.dependency.DependencyResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainDiffResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.dependency.DependencyRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainDiffMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DependencyMapper;
import org.qubership.integration.platform.runtime.catalog.service.DependencyService;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v1/chains/{chainId}/dependencies", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "dependency-controller", description = "Dependency Controller")
public class DependencyController {

    private final DependencyService dependencyService;
    private final DependencyMapper dependencyMapper;
    private final ChainDiffMapper chainDiffMapper;
    private final ElementService elementService;

    @Autowired
    public DependencyController(DependencyService dependencyService,
                                DependencyMapper dependencyMapper,
                                ChainDiffMapper chainDiffMapper,
                                ElementService elementService) {
        this.dependencyService = dependencyService;
        this.dependencyMapper = dependencyMapper;
        this.chainDiffMapper = chainDiffMapper;
        this.elementService = elementService;
    }

    @GetMapping
    @Operation(description = "Find all dependencies by chain")
    public ResponseEntity<List<DependencyResponse>> findAllByChainId(@PathVariable @Parameter(description = "Chain id") String chainId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to receive dependencies from chain: {}", chainId);
        }
        var elements = elementService.findAllByChainId(chainId);
        var response = dependencyMapper.extractDependencies(elements);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{dependencyId}")
    @Operation(description = "Find specific dependency in the chain")
    public ResponseEntity<DependencyResponse> findById(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                       @PathVariable @Parameter(description = "Dependency id") String dependencyId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find dependency {} in chain {}", chainId, dependencyId);
        }
        var entity = dependencyService.findById(dependencyId);
        var response = dependencyMapper.asResponse(entity);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(description = "Create new dependency for the chain")
    public ResponseEntity<ChainDiffResponse> create(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                    @RequestBody @Parameter(description = "Dependency object") DependencyRequest request) {
        logCreation(chainId, request);
        String from = request.getFrom();
        String to = request.getTo();

        ChainDiff chainDiff = dependencyService.create(from, to);
        ChainDiffResponse response = chainDiffMapper.asResponse(chainDiff);
        return ResponseEntity.ok(response);
    }

    @Deprecated
    @DeleteMapping("/{dependencyId}")
    @Operation(description = "Delete specific dependency in the chain")
    public ResponseEntity<ChainDiffResponse> deleteById(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                        @PathVariable @Parameter(description = "Dependency id") String dependencyId) {
        log.info("Request to delete dependency {} from chain {}", dependencyId, chainId);
        ChainDiff chainDiff = dependencyService.deleteById(dependencyId);
        ChainDiffResponse response = chainDiffMapper.asResponse(chainDiff);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("")
    @Operation(description = "Delete specified dependencies in the chain")
    public ResponseEntity<ChainDiffResponse> deleteByIds(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                         @RequestParam @Parameter(description = "Dependency ids separated by comma") List<String> dependenciesIds) {
        log.info("Request to delete dependencies {} from chain {}", dependenciesIds, chainId);
        ChainDiff chainDiff = dependencyService.deleteAllByIds(dependenciesIds);
        ChainDiffResponse response = chainDiffMapper.asResponse(chainDiff);
        return ResponseEntity.ok(response);
    }

    private void logCreation(String chainId, DependencyRequest dependency) {
        var from = dependency.getFrom();
        var to = dependency.getTo();
        log.info("Request to add dependency from {} to {} in chain {}",
                from,
                to,
                chainId);
    }
}
