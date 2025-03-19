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
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qubership.integration.platform.catalog.model.ElementRoute;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.validation.RouteDeployment;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.ElementValidationService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@RestController
@RequestMapping(value = "/v1/catalog/validation", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "element-validation-controller", description = "Element Validation Controller")
public class ElementValidationController {
    private final ElementValidationService elementValidationService;
    private final RuntimeDeploymentService runtimeDeploymentService;
    private final DeploymentMapper deploymentMapper;

    @Autowired
    public ElementValidationController(
            ElementValidationService elementValidationService,
            RuntimeDeploymentService runtimeDeploymentService,
            DeploymentMapper deploymentMapper
    ) {
        this.elementValidationService = elementValidationService;
        this.runtimeDeploymentService = runtimeDeploymentService;
        this.deploymentMapper = deploymentMapper;
    }

    @GetMapping("/routes")
    @Operation(description = "UI check for route existence on http trigger")
    public ResponseEntity<Boolean> checkRouteExists(
            @RequestParam @Parameter(description = "Uri") String uri,
            @RequestParam @Parameter(description = "Id of chains to exclude from check") String excludeChainId,
            @RequestParam(required = false, defaultValue = "true") @Parameter(description = "Whether to check external routes") boolean isExternalRoute,
            @RequestParam(required = false, defaultValue = "false") @Parameter(description = "Whether to check private routes") boolean isPrivateRoute,
            @ArraySchema(uniqueItems = true, schema = @Schema(type = "string", allowableValues = {
                    "GET",
                    "HEAD",
                    "POST",
                    "PUT",
                    "PATCH",
                    "DELETE",
                    "OPTIONS",
                    "TRACE"
            }))
            @Parameter(description = "HTTP method")
            @RequestParam(required = false, defaultValue = "") Set<HttpMethod> httpMethods
    ) {
        return ResponseEntity.ok(elementValidationService.checkRouteExists(
                buildRoute(uri, httpMethods, isExternalRoute, isPrivateRoute), excludeChainId));
    }

    @GetMapping("/findRouteDeployments")
    @Operation(description = "Check for route existence and respond with deployment using it")
    public ResponseEntity<List<RouteDeployment>> findRouteDeployments(
            @RequestParam @Parameter(description = "Uri") String uri,
            @RequestParam @Parameter(description = "Id of chains to exclude from check") String excludeChainId,
            @RequestParam(required = false, defaultValue = "true") @Parameter(description = "Whether to check external routes") boolean isExternalRoute,
            @RequestParam(required = false, defaultValue = "false") @Parameter(description = "Whether to check private routes") boolean isPrivateRoute,
            @ArraySchema(uniqueItems = true, schema = @Schema(type = "string", allowableValues = {
                    "GET",
                    "HEAD",
                    "POST",
                    "PUT",
                    "PATCH",
                    "DELETE",
                    "OPTIONS",
                    "TRACE"
            }))
            @Parameter(description = "HTTP method")
            @RequestParam(required = false, defaultValue = "") Set<HttpMethod> httpMethods
    ) {
        List<RouteDeployment> response =
                elementValidationService
                        .findRouteDeployments(buildRoute(uri, httpMethods, isExternalRoute, isPrivateRoute), excludeChainId)
                        .stream()
                        .map(pair -> {
                            String path = pair.getLeft();
                            Deployment deployment = pair.getRight();
                            RuntimeDeployment runtimeState = runtimeDeploymentService.getRuntimeDeployment(deployment.getId());
                            return RouteDeployment.builder()
                                    .path(path)
                                    .deployment(deploymentMapper.asResponse(deployment, runtimeState))
                                    .build();
                        })
                        .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    private static ElementRoute buildRoute(String uri, Set<HttpMethod> httpMethods, boolean isExternalRoute, boolean isPrivateRoute) {
        return ElementRoute.builder()
                .path(uri)
                .methods(httpMethods.isEmpty() ? Set.of(HttpMethod.values()) : httpMethods)
                .isExternal(isExternalRoute)
                .isPrivate(isPrivateRoute)
                .build();
    }
}
