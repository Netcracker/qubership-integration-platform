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
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.LibraryElements;
import org.qubership.integration.platform.runtime.catalog.model.dto.chain.ElementsFilterDTO;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CONTAINER;

@Slf4j
@RestController
@RequestMapping(value = "/v1/library", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "element-library-controller", description = "Element Library Controller")
public class ElementLibraryController {

    private final LibraryElementsService libraryElementsService;
    private final ElementService elementService;

    @Autowired
    public ElementLibraryController(
        LibraryElementsService libraryElementsService,
        ElementService elementService
    ) {
        this.libraryElementsService = libraryElementsService;
        this.elementService = elementService;
    }

    @GetMapping
    @Operation(description = "Get all library elements (descriptors)")
    public ResponseEntity<LibraryElements> getElementsHierarchy() {
        return ResponseEntity.ok(libraryElementsService.getElementsHierarchy());
    }

    @GetMapping("/{name}")
    @Operation(description = "Get library element by it's inner type name")
    public ResponseEntity<ElementDescriptor> getLibraryElement(@PathVariable @Parameter(description = "Inner type name") String name) {
        if (log.isDebugEnabled()) {
            log.debug("Request to receive element with name: {}", name);
        }
        ElementDescriptor element = libraryElementsService.getElementDescriptor(name);
        if (element != null) {
            return ResponseEntity.ok(element);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/elements/types")
    @Operation(description = "Find all library elements inner types and title names")
    public ResponseEntity<List<ElementsFilterDTO>> findAllUsingElementsTypes() {
        List<ElementsFilterDTO> titles = elementService.findAllUsingTypes()
            .stream()
            .filter(type -> !CONTAINER.equals(type))
            .map(type -> new ElementsFilterDTO(libraryElementsService.getElementDescriptor(type).getTitle(), type))
            .toList();
        return ResponseEntity.ok(titles);
    }
}
