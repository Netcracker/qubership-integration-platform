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
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineDeploymentsDTO;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentsUpdate;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubeDeployment;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.EngineDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.DomainResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.EngineResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.EngineMapper;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/domains")
@CrossOrigin(origins = "*")
@Tag(name = "engine-controller", description = "Engine Controller")
public class EngineController {
    private final EngineMapper engineMapper;
    private final EngineService engineService;
    private final DeploymentMapper deploymentMapper;
    private final DeploymentService deploymentService;
    private final RuntimeDeploymentService runtimeDeploymentService;

    @Autowired
    EngineController(EngineService engineService,
                     EngineMapper engineMapper,
                     DeploymentMapper deploymentMapper,
                     DeploymentService deploymentService,
                     RuntimeDeploymentService runtimeDeploymentService) {
        this.engineService = engineService;
        this.engineMapper = engineMapper;
        this.deploymentMapper = deploymentMapper;
        this.deploymentService = deploymentService;
        this.runtimeDeploymentService = runtimeDeploymentService;
    }

    @GetMapping
    @Operation(description = "Get all available engine domains")
    ResponseEntity<List<DomainResponse>> getDomains() {
        List<KubeDeployment> domains = engineService.getDomains();
        return ResponseEntity.ok(engineMapper.asDomainResponses(domains));
    }

    @GetMapping("/{domainName}/engines")
    @Operation(description = "Get available engines for the domain")
    ResponseEntity<List<EngineResponse>> getEngines(@PathVariable @Parameter(description = "Engine domain name") String domainName) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get engines by domain {}", domainName);
        }
        return ResponseEntity.ok(engineMapper.asEngineResponses(engineService.getEnginesPods(domainName)));
    }

    @GetMapping("/hosts")
    @Operation(description = "Get all engine hosts in format <domain, list of ip>")
    public ResponseEntity<Map<String, List<String>>> getAllEngineHosts() {
        if (log.isDebugEnabled()) {
            log.debug("Request to get hosts with domain");
        }
        return ResponseEntity.ok(runtimeDeploymentService.getEngineHosts());
    }

    @GetMapping("/{domainName}/engines/{engineHost}/deployments")
    @Operation(description = "Get deployments from specified engine pod")
    ResponseEntity<List<EngineDeploymentResponse>> getEngineDeployments(@PathVariable @Parameter(description = "Engine domain name") String domainName,
                                                                        @PathVariable @Parameter(description = "Engine pod's ip address") String engineHost) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get engine deployments by domain {} and engine host {}", domainName, engineHost);
        }
        return ResponseEntity.ok(deploymentMapper.asEngineDeployments(
                runtimeDeploymentService.findRuntimeDeployments(engineHost)));
    }

    @GetMapping("/{domainName}/deployments/count")
    @Operation(description = "Get amount of deployments on specified domain")
    ResponseEntity<Long> getDeploymentsCountByDomain(@PathVariable @Parameter(description = "Engine domain name") String domainName) {
        return ResponseEntity.ok(engineService.deploymentsCountByDomain(domainName));
    }


    /**
     * Internal endpoint, used for communication with qip-engine
     */
    @PostMapping(path = "/{domainName}/deployments/update", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Get deployments update for particular engine, for internal use")
    ResponseEntity<DeploymentsUpdate> getDeploymentsUpdate(@PathVariable @Parameter(description = "Engine domain name") String domainName,
                                                           @RequestBody @Parameter(description = "Engine deployments update request object") EngineDeploymentsDTO engineDeployments) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get deployments for engine with domain {}", domainName);
        }
        try {
            DeploymentsUpdate deploymentsUpdate =
                    deploymentService.getDeploymentsForDomain(domainName, engineDeployments);
            return ResponseEntity.ok(deploymentsUpdate);
        } catch (Exception e) {
            log.error("Failed to get deployments for engine update", e);
            throw e;
        }
    }
}
