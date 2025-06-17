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
import org.qubership.integration.platform.runtime.catalog.service.ElementModificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v1/chains/{chainId}/elements/properties-modification", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "element-modification-controller", description = "Element Modification Controller")
public class ElementModificationController {
    private final ElementModificationService elementModificationService;

    @Autowired
    public ElementModificationController(ElementModificationService elementModificationService) {
        this.elementModificationService = elementModificationService;
    }

    @PutMapping
    @Operation(description = "Change specified http triggers type to implemented with specified specification group")
    public ResponseEntity<Void> modifyHttpTriggerProperties(@PathVariable(required = false) @Parameter(description = "Chain id") String chainId,
                                                            @RequestParam @Parameter(description = "Specification group id") String specificationGroupId,
                                                            @RequestParam @Parameter(description = "List of http trigger elements separated by comma") List<String> httpTriggerIds) {
        this.elementModificationService.makeHttpTriggersTypeImplemented(httpTriggerIds, specificationGroupId);
        return ResponseEntity.noContent().build();
    }
}
