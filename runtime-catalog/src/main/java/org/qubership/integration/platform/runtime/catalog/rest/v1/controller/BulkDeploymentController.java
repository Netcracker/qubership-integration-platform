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
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping(path = {"/v1/catalog/chains/deployments", "/api/v1/cip/catalog/chains/deployments"},
    produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "bulk-deployment-controller", description = "Bulk Deployment Controller")
public class BulkDeploymentController {
    private final BulkDeploymentService bulkDeploymentService;

    @Autowired
    public BulkDeploymentController(BulkDeploymentService bulkDeploymentService) {
        this.bulkDeploymentService = bulkDeploymentService;
    }

    @PostMapping("/bulk")
    @Operation(description = "(Re)deploy chains in bulk mode")
    public ResponseEntity<List<BulkDeploymentResponse>> bulkCreate(@RequestBody @Valid @Parameter(description = "Chain bulk deploy request object") BulkDeploymentRequest request) {
        log.info("Request to bulk redeploy chains");
        List<BulkDeploymentResponse> result = new ArrayList<>();
        bulkDeploymentService.deployChains(request, result::add);
        boolean failed = result.stream().anyMatch(response ->
                BulkDeploymentStatus.FAILED_DEPLOY.equals(response.getStatus())
                        || BulkDeploymentStatus.FAILED_SNAPSHOT.equals(response.getStatus()));
        return failed
                ? ResponseEntity.status(HttpStatus.MULTI_STATUS).body(result)
                : ResponseEntity.ok(result);
    }
}
