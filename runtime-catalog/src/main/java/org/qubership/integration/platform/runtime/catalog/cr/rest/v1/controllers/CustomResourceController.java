package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/v1/cr")
@Deprecated(since = "2026.4")
@Tag(
        name = "custom-resource-controller",
        description = "Custom Resource Build and Deploy Controller"
)
public class CustomResourceController {
    private final BulkDeploymentService bulkDeploymentService;
    private final MicroDomainResourceBuildService microDomainResourceBuildService;
    private final MicroDomainService microDomainService;

    @Autowired
    public CustomResourceController(
            BulkDeploymentService bulkDeploymentService,
            MicroDomainResourceBuildService microDomainResourceBuildService,
            MicroDomainService microDomainService
    ) {
        this.bulkDeploymentService = bulkDeploymentService;
        this.microDomainResourceBuildService = microDomainResourceBuildService;
        this.microDomainService = microDomainService;
    }

    @PostMapping(produces = MediaType.APPLICATION_YAML_VALUE)
    @Operation(description = "Build K8s resources for specified chain snapshots")
    @Deprecated(since = "2026.4")
    public String buildResource(@RequestBody ResourceBuildRequest request) {
        log.debug("Request to build a CR for snapshots: {}", request.getSnapshotIds());
        return microDomainService.verifyMicroDomainEnabled(() ->
                microDomainResourceBuildService.buildResources(request, false).yaml());
    }

    @PostMapping("/deploy")
    @Operation(description = "Deploy as Camel-K Integration resource")
    @Deprecated(since = "2026.4")
    public ResponseEntity<Void> deployResource(@Valid @RequestBody ResourceDeployRequest request) {
        log.debug("Request to deploy a Camel-K custom resource with name {} for chain snapshots {} using {} mode.",
                request.getName(), request.getSnapshotIds(), request.getMode());
        return microDomainService.verifyMicroDomainEnabled(() -> {
            bulkDeploymentService.deployResource(request);
            return ResponseEntity.ok().build();
        });
    }
}
