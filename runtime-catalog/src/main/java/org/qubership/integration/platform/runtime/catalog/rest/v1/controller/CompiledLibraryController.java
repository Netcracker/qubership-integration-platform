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
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static java.util.Objects.isNull;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/models/{modelId}/dto/jar")
@Tag(name = "compiled-library-controller", description = "Compiled Library Controller")
public class CompiledLibraryController {

    private final SystemModelService systemModelService;

    @Autowired
    public CompiledLibraryController(SystemModelService systemModelService) {
        this.systemModelService = systemModelService;
    }

    @GetMapping(produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(description = "Get compiled jar file for the specification")
    public ResponseEntity<Object> getSystemModelJar(@PathVariable @Parameter(description = "Specification id") String modelId) {
        log.debug("Request to get jar file for model with id {}", modelId);
        Pair<byte[], String> pair = systemModelService.getCompiledLibrary(modelId);
        return isNull(pair)
                ? ResponseEntity.noContent().build()
                : ExportImportUtils.convertFileToResponse(pair.getFirst(), pair.getSecond());
    }
}
