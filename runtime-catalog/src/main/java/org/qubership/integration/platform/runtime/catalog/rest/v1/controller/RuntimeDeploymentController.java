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

import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.ChainRuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/runtime-deployments", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "runtime-deployment-controller", description = "Runtime Deployment Controller")
public class RuntimeDeploymentController {

    private static final String DEPLOYMENT_INFO = "deploymentInfo";
    private static final String DEPLOYMENT_INFO_PREFIX = DEPLOYMENT_INFO + ".";

    private final RuntimeDeploymentService runtimeDeploymentService;

    @Autowired
    public RuntimeDeploymentController(RuntimeDeploymentService runtimeDeploymentService) {
        this.runtimeDeploymentService = runtimeDeploymentService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Get deployment statuses for all chains on all available engine pods")
    public ResponseEntity<MappingJacksonValue> findChainRuntimeDeployments(
            @RequestParam(value = "fields", required = false) @Parameter(description = "Fields to include in the response") String[] fields
    ) {
        Map<String, Collection<ChainRuntimeDeployment>> deployments = runtimeDeploymentService.getChainRuntimeDeployments();
        if (deployments == null || deployments.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        MappingJacksonValue result = new MappingJacksonValue(deployments);
        if (ArrayUtils.isNotEmpty(fields)) {
            result.setFilters(createFilters(fields));
        }

        return ResponseEntity.ok(result);
    }

    private FilterProvider createFilters(String[] fields) {
        Set<String> chainRuntimeDeploymentFields = new HashSet<>();
        Set<String> deploymentInfoFields = new HashSet<>();

        for (String field : fields) {
            if (field.startsWith(DEPLOYMENT_INFO_PREFIX)) {
                deploymentInfoFields.add(field.substring(DEPLOYMENT_INFO_PREFIX.length()));
            } else {
                chainRuntimeDeploymentFields.add(field);
            }
        }

        if (!deploymentInfoFields.isEmpty()) {
            chainRuntimeDeploymentFields.add(DEPLOYMENT_INFO);
        }

        return new SimpleFilterProvider()
                .addFilter("ChainRuntimeDeploymentFilter", SimpleBeanPropertyFilter.filterOutAllExcept(chainRuntimeDeploymentFields))
                .addFilter("DeploymentInfoFilter", SimpleBeanPropertyFilter.filterOutAllExcept(deploymentInfoFields));
    }
}
