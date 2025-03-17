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
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/chains/deployments", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "bulk-deployment-controller", description = "Bulk Deployment Controller")
public class BulkDeploymentController {
    private final DeploymentService deploymentService;

    @Autowired
    public BulkDeploymentController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @PostMapping("/bulk")
    @Operation(description = "(Re)deploy chains in bulk mode")
    public ResponseEntity<List<BulkDeploymentResponse>> bulkCreate(@RequestBody @Valid @Parameter(description = "Chain bulk deploy request object") BulkDeploymentRequest request) {
        log.info("Request to bulk redeploy chains");
        Pair<Boolean, List<BulkDeploymentResponse>> bulkDeploymentResponses = deploymentService.bulkCreate(request);
        return bulkDeploymentResponses.getLeft() ?
                ResponseEntity.status(HttpStatus.MULTI_STATUS).body(bulkDeploymentResponses.getRight()) :
                ResponseEntity.ok(bulkDeploymentResponses.getRight());
    }
}
