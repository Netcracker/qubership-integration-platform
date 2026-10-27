package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployWithSnapshotCreationRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;

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
    private final DomainProperties domainProperties;

    @Autowired
    public CustomResourceController(
            BulkDeploymentService bulkDeploymentService,
            MicroDomainResourceBuildService microDomainResourceBuildService,
            MicroDomainService microDomainService,
            DomainProperties domainProperties
    ) {
        this.bulkDeploymentService = bulkDeploymentService;
        this.microDomainResourceBuildService = microDomainResourceBuildService;
        this.microDomainService = microDomainService;
        this.domainProperties = domainProperties;
    }

    @PostMapping(produces = MediaType.APPLICATION_YAML_VALUE)
    @Operation(description = "Build K8s resources for specified chain snapshots")
    @Deprecated(since = "2026.4")
    public String buildResource(@RequestBody ResourceBuildRequest request) {
        log.debug("Request to build a CR for snapshots: {}", request.getSnapshotIds());
        return verifyMicroDomainEnabled(() ->
                microDomainResourceBuildService.buildResources(request, false).yaml());
    }

    @PostMapping("/deploy-chains")
    @Operation(description = "Deploy with creation of snapshots as Camel-K Integration resource")
    @Deprecated(since = "2026.4")
    public ResponseEntity<List<BulkDeploymentResponse>> deployChains(
            @Valid @RequestBody DeployWithSnapshotCreationRequest request
    ) {
        List<BulkDeploymentResponse> result = new ArrayList<>();
        bulkDeploymentService.deployChains(request, result::add);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/deploy")
    @Operation(description = "Deploy as Camel-K Integration resource")
    @Deprecated(since = "2026.4")
    public ResponseEntity<Void> deployResource(@Valid @RequestBody ResourceDeployRequest request) {
        log.debug("Request to deploy a Camel-K custom resource with name {} for chain snapshots {} using {} mode.",
                request.getName(), request.getSnapshotIds(), request.getMode());
        return verifyMicroDomainEnabled(() -> {
            bulkDeploymentService.deployResource(request);
            return ResponseEntity.ok().build();
        });
    }

    @DeleteMapping("/{name}")
    @Operation(description = "Delete Camel-K Integration resource")
    @Deprecated(since = "2026.4")
    public ResponseEntity<Void> deleteResource(@PathVariable String name) {
        log.debug("Request to delete a Camel-K custom resource with name {}", name);
        return verifyMicroDomainEnabled(() -> {
            microDomainService.delete(name);
            return ResponseEntity.ok().build();
        });
    }

    @DeleteMapping("/{name}/{snapshotId}")
    @Operation(description = "Delete integration chain snapshot from Camel-K resource")
    @Deprecated(since = "2026.4")
    public ResponseEntity<Void> deleteSnapshotFromResource(@PathVariable String name, @PathVariable String snapshotId) {
        log.debug("Request to delete chain snapshot {} from a Camel-K custom resource {}", snapshotId, name);
        return verifyMicroDomainEnabled(() -> {
            bulkDeploymentService.deleteChainSnapshot(name, snapshotId);
            return ResponseEntity.ok().build();
        });
    }

    private <T> T verifyMicroDomainEnabled(Supplier<T> supplier) {
        if (domainProperties.getMicro().isEnabled()) {
            return supplier.get();
        } else {
            throw new DomainTypeDisabledException(DomainType.MICRO);
        }
    }
}
