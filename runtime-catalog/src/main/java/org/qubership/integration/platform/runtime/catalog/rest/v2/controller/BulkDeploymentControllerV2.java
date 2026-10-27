package org.qubership.integration.platform.runtime.catalog.rest.v2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployWithSnapshotCreationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value = {"/v2/catalog/chains/deployments", "/api/cip/v2/catalog/chains/deployments"},
        produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@SuppressWarnings("java:S5122")
@Tag(name = "bulk-deployment-controller-v-2", description = "Bulk Deployment Controller V2")
public class BulkDeploymentControllerV2 {
    private final BulkDeploymentService bulkDeploymentService;

    @Autowired
    public BulkDeploymentControllerV2(BulkDeploymentService bulkDeploymentService) {
        this.bulkDeploymentService = bulkDeploymentService;
    }

    @PostMapping("/bulk")
    @Operation(description = "Deploy with creation of snapshots as Camel-K Integration resource")
    public ResponseEntity<List<BulkDeploymentResponse>> deployChains(
            @Valid @RequestBody DeployWithSnapshotCreationRequest request
    ) {
        List<BulkDeploymentResponse> result = new ArrayList<>();
        bulkDeploymentService.deployChains(request, result::add);
        return ResponseEntity.ok(result);
    }
}
