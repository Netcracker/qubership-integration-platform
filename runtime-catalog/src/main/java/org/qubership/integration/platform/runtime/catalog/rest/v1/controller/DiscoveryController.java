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
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveredServiceDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveryResultDTO;
import org.qubership.integration.platform.runtime.catalog.service.DiscoveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/v1/systems/discovery", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "discovery-controller", description = "Discovery Controller")
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    @Autowired
    public DiscoveryController(
            DiscoveryService discoveryService
    ) {
        this.discoveryService = discoveryService;
    }

    @GetMapping
    @Operation(description = "Get all currently discovered services")
    public List<DiscoveredServiceDTO> getServices() {
        return discoveryService.getServices();
    }

    @PostMapping
    @Operation(description = "Initiate discovery process on a current environment")
    public Object runDiscovery() {
        discoveryService.runDiscovery();
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/result")
    @Operation(description = "Get result of a discovery process initiated earlier")
    public DiscoveryResultDTO getDiscoveryResult() {
        return discoveryService.getDiscoveryResult();
    }

    @GetMapping("/progress")
    @Operation(description = "Get status of a discovery process initiated earlier")
    public String getDiscoveryProgress() {
        return discoveryService.getDiscoveryProgress();
    }
}
