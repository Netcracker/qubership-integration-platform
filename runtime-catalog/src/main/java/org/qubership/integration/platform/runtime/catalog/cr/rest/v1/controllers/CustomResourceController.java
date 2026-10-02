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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
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
@Tag(
        name = "custom-resource-controller",
        description = "Custom Resource Build and Deploy Controller"
)
public class CustomResourceController {
    /** Attempts any single write sequence gets before a lost concurrency race is surfaced. */
    private static final int MAX_CONFLICT_ATTEMPTS = 3;

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
    public String buildResource(@RequestBody ResourceBuildRequest request) {
        log.debug("Request to build a CR for snapshots: {}", request.getSnapshotIds());
        return verifyMicroDomainEnabled(() ->
                microDomainResourceBuildService.buildResources(request, false).yaml());
    }

    @PostMapping("/deploy-chains")
    @Operation(description = "Deploy with creation of snapshots as Camel-K Integration resource")
    public ResponseEntity<List<BulkDeploymentResponse>> deployChains(
            @Valid @RequestBody DeployWithSnapshotCreationRequest request
    ) {
        List<BulkDeploymentResponse> result = bulkDeploymentService.deployChains(request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/deploy")
    @Operation(description = "Deploy as Camel-K Integration resource")
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
    public ResponseEntity<Void> deleteResource(@PathVariable String name) {
        log.debug("Request to delete a Camel-K custom resource with name {}", name);
        return verifyMicroDomainEnabled(() -> {
            microDomainService.delete(name);
            return ResponseEntity.ok().build();
        });
    }

    @DeleteMapping("/{name}/{snapshotId}")
    @Operation(description = "Delete integration chain snapshot from Camel-K resource")
    public ResponseEntity<Void> deleteSnapshotFromResource(@PathVariable String name, @PathVariable String snapshotId) {
        log.debug("Request to delete chain snapshot {} from a Camel-K custom resource {}", snapshotId, name);
        return verifyMicroDomainEnabled(() -> {
            doDeleteChainSnapshot(name, snapshotId);
            return ResponseEntity.ok().build();
        });
    }

    /**
     * Removes {@code snapshotId} from the micro-domain, retrying up to
     * {@link #MAX_CONFLICT_ATTEMPTS} times when a write loses an optimistic-concurrency race.
     *
     * <p>{@code deleteChainSnapshot} rewrites the Integration, the integrations-configuration
     * ConfigMap and the shared HTTPRoute tiers, each carrying the {@code resourceVersion} it read
     * on entry, so a deploy to the same domain running alongside it can take any of those writes.
     * Re-reading is the whole recovery: the method reloads everything through
     * {@code getMainIntegrationResources}, so another attempt recomputes against current state
     * rather than replaying a decision made against stale reads. Unlike the deploy path there is
     * no built document to rebuild, so the call itself is the retry unit.
     *
     * <p>An attempt is safe over the steps an earlier one already completed. A source ConfigMap the
     * earlier attempt deleted leaves {@code cfgName} empty on the next pass, which keeps every
     * mount and skips the delete, so its mount removal stands rather than being undone or repeated.
     * The configuration entry it removed is simply absent from the reloaded sources, and the
     * subtraction that would have removed it becomes a no-op.
     */
    private void doDeleteChainSnapshot(String name, String snapshotId) {
        for (int attempt = 1; ; attempt++) {
            try {
                microDomainService.deleteChainSnapshot(name, snapshotId);
                return;
            } catch (KubeApiConflictException conflict) {
                if (attempt == MAX_CONFLICT_ATTEMPTS) {
                    throw conflict;
                }
                log.warn("Removal of snapshot '{}' from micro-domain '{}' lost a concurrency race on "
                                + "attempt {}/{}; re-reading current cluster state and retrying",
                        snapshotId, name, attempt, MAX_CONFLICT_ATTEMPTS);
            }
        }
    }

    private <T> T verifyMicroDomainEnabled(Supplier<T> supplier) {
        if (domainProperties.getMicro().isEnabled()) {
            return supplier.get();
        } else {
            throw new DomainTypeDisabledException(DomainType.MICRO);
        }
    }
}
