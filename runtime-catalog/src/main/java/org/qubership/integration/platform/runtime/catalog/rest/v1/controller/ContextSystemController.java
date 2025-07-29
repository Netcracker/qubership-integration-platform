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

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemSearchRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.context.ContextSystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.context.ContextSystemResponseDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.context.ContextSystemUpdateRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ContextSystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.ContextSystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@ComponentScan
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/v1/context-system", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "context-system-controller", description = "Context System Controller")
public class ContextSystemController {

    private final ContextSystemService contextSystemService;
    private final ContextSystemMapper contextSystemMapper;

    @Autowired
    public ContextSystemController(ContextSystemService contextSystemService, ContextSystemMapper contextSystemMapper) {
        this.contextSystemService = contextSystemService;
        this.contextSystemMapper = contextSystemMapper;
    }

    @GetMapping
    public ResponseEntity<List<ContextSystemResponseDTO>> getContextSystems() {
        if (log.isDebugEnabled()) {
            log.debug("Request to find all context systems");
        }
        List<ContextSystem> contextSystems = contextSystemService.findAll();
        List<ContextSystemResponseDTO> response
                = contextSystemMapper.toContextSystemResponsesDTOs(contextSystems);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{contextId}")
    public ResponseEntity<ContextSystemResponseDTO> geContextSystemById(@PathVariable String contextId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find context system with id: {}", contextId);
        }
        ContextSystem contextSystem = contextSystemService.findById(contextId);
        ContextSystemResponseDTO response = contextSystemMapper.toContextSystemResponseDTO(contextSystem);
        return ResponseEntity.ok(response);
    }


    @PostMapping
    public ResponseEntity<ContextSystemResponseDTO> createContextSystem(@RequestBody ContextSystemRequestDTO context) {
        log.info("Request to create context system: {}", context);
        ContextSystem createdContextSystem = contextSystemService.create(context);
        return new ResponseEntity<>(contextSystemMapper.toContextSystemResponseDTO(createdContextSystem), HttpStatus.CREATED);
    }


    @PutMapping("/{contextId}")
    public ResponseEntity<ContextSystemResponseDTO> updateContextSystem(@PathVariable String contextId, @RequestBody ContextSystemUpdateRequestDTO request) {
        log.info("Request to update context system with id: {}", contextId);
        ContextSystem contextSystem = contextSystemService.update(request, contextId);
        return ResponseEntity.ok(contextSystemMapper.toContextSystemResponseDTO(contextSystem));
    }

    @DeleteMapping("/{contextId}")
    public ResponseEntity<Void> deleteContextSystemById(@PathVariable String contextId) {
        log.info("Request to delete context system with id {} ", contextId);
        contextSystemService.deleteById(contextId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/search", produces = "application/json")
    public ResponseEntity<List<ContextSystemResponseDTO>> searchContextSystems(@RequestBody SystemSearchRequestDTO systemSearchRequestDTO) {
        List<ContextSystem> contextSystems = contextSystemService.searchContextSystems(systemSearchRequestDTO);
        List<ContextSystemResponseDTO> response = contextSystemMapper.toContextSystemResponsesDTOs(contextSystems);

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/filter", produces = "application/json")
    public ResponseEntity<List<ContextSystemResponseDTO>> filterContextSystems(@RequestBody List<FilterRequestDTO> systemFilterRequestDTOList) {
        List<ContextSystem> contextSystemsFilterResult = contextSystemService.findByFilterRequest(systemFilterRequestDTOList);
        List<ContextSystemResponseDTO> response = contextSystemMapper.toContextSystemResponsesDTOs(contextSystemsFilterResult);

        return ResponseEntity.ok(response);
    }
}

