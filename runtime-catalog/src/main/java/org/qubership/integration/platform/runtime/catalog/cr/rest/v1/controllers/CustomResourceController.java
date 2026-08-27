package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.BuiltResources;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployMode;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployWithSnapshotCreationRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.cr.services.ResourceBuildOptionsProvider;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

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

    private final MicroDomainResourceBuildService microDomainResourceBuildService;
    private final MicroDomainService microDomainService;
    private final ResourceBuildOptionsProvider resourceBuildOptionsProvider;
    private final DeploymentService deploymentService;
    private final ChainRepository chainRepository;
    private final EngineService engineService;
    private final DomainProperties domainProperties;

    @Autowired
    public CustomResourceController(
            MicroDomainResourceBuildService microDomainResourceBuildService,
            MicroDomainService microDomainService,
            ResourceBuildOptionsProvider resourceBuildOptionsProvider,
            DeploymentService deploymentService,
            ChainRepository chainRepository,
            EngineService engineService,
            DomainProperties domainProperties
    ) {
        this.microDomainResourceBuildService = microDomainResourceBuildService;
        this.microDomainService = microDomainService;
        this.resourceBuildOptionsProvider = resourceBuildOptionsProvider;
        this.deploymentService = deploymentService;
        this.chainRepository = chainRepository;
        this.engineService = engineService;
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
    @Transactional
    public ResponseEntity<List<BulkDeploymentResponse>> deployChains(
            @Valid @RequestBody DeployWithSnapshotCreationRequest request
    ) {
        List<BulkDeploymentResponse> result = new ArrayList<>();
        Collection<Chain> chains = chainRepository.findAllById(request.getChainIds()).stream()
                .filter(chain -> {
                    boolean isOverridden = nonNull(chain.getOverriddenByChainId());
                    if (isOverridden) {
                        result.add(BulkDeploymentResponse.builder()
                                .chainId(chain.getId())
                                .chainName(chain.getName())
                                .status(BulkDeploymentStatus.IGNORED)
                                .build());
                    }
                    return !isOverridden;
                })
                .toList();
        Collection<Snapshot> snapshots = deploymentService.provideSnapshots(
                chains.stream().map(Chain::getId).toList(),
                request.getSnapshotAction(),
                (chainId, msg) -> result.add(BulkDeploymentResponse.builder()
                                .chainName(chains.stream()
                                        .filter(chain -> chain.getId().equals(chainId))
                                        .findFirst()
                                        .map(Chain::getName)
                                        .orElse(null)
                                )
                                .chainId(chainId)
                                .status(BulkDeploymentStatus.FAILED_SNAPSHOT)
                                .errorMessage(msg)
                        .build()))
                .values();

        Map<String, DomainType> domainTypeMap = engineService.getDomains().stream()
                .collect(Collectors.toMap(
                        EngineDomain::getName,
                        EngineDomain::getType
                ));
        Map<DomainType, List<String>> domainByType = request.getDomains()
                .stream()
                .collect(Collectors.groupingBy(
                        name -> domainTypeMap.getOrDefault(name, DomainType.MICRO)));

        snapshots.stream()
                .map(snapshot -> deploymentService.deploySnapshot(
                    snapshot,
                    domainByType.getOrDefault(DomainType.CLASSIC, Collections.emptyList())))
                .flatMap(Collection::stream)
                .forEach(result::add);

        domainByType.getOrDefault(DomainType.MICRO, Collections.emptyList()).stream()
                .map(name -> {
                    try {
                        doDeployResource(ResourceDeployRequest.builder()
                                .name(name)
                                .mode(request.getMode())
                                .snapshotIds(snapshots.stream().map(Snapshot::getId).toList())
                                .build());
                        return snapshots.stream()
                                .map(snapshot -> BulkDeploymentResponse.builder()
                                        .chainId(snapshot.getChain().getId())
                                        .chainName(snapshot.getChain().getName())
                                        .status(BulkDeploymentStatus.CREATED)
                                        .domain(EngineDomain.builder()
                                                .name(name)
                                                .type(DomainType.MICRO)
                                                .build())
                                        .build())
                                .toList();
                    } catch (Exception e) {
                        return snapshots.stream()
                                .map(snapshot -> BulkDeploymentResponse.builder()
                                        .chainId(snapshot.getChain().getId())
                                        .chainName(snapshot.getChain().getName())
                                        .status(BulkDeploymentStatus.FAILED_DEPLOY)
                                        .errorMessage(e.getMessage())
                                        .domain(EngineDomain.builder()
                                                .name(name)
                                                .type(DomainType.MICRO)
                                                .build())
                                        .build())
                                .toList();
                    }
                }).forEach(result::addAll);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/deploy")
    @Operation(description = "Deploy as Camel-K Integration resource")
    public ResponseEntity<Void> deployResource(@Valid @RequestBody ResourceDeployRequest request) {
        log.debug("Request to deploy a Camel-K custom resource with name {} for chain snapshots {} using {} mode.",
                request.getName(), request.getSnapshotIds(), request.getMode());
        return verifyMicroDomainEnabled(() -> {
            doDeployResource(request);
            return ResponseEntity.ok().build();
        });
    }

    /**
     * Builds the resources for {@code request} and writes them, rebuilding from scratch and retrying
     * up to {@link #MAX_CONFLICT_ATTEMPTS} times when a write loses an optimistic-concurrency race.
     *
     * <p>The build request is constructed inside the loop, not hoisted out of it. The build mutates
     * the options it is handed -- {@code MicroDomainResourceBuildContextFactory} unions the live
     * Integration's mounts into {@code options.mount} in place -- so a request shared across
     * attempts would carry the previous attempt's merge into the next one. The mount set could then
     * only grow, and a mount the conflicting writer had removed would come back, re-mounting a
     * ConfigMap that no longer exists. {@code ResourceBuildOptionsProvider.getOptions} is property
     * binding plus customizers, so rebuilding it per attempt is cheap.
     */
    private void doDeployResource(ResourceDeployRequest request) {
        for (int attempt = 1; ; attempt++) {
            ResourceBuildRequest buildRequest = ResourceBuildRequest.builder()
                    .options(resourceBuildOptionsProvider.getOptions(request))
                    .snapshotIds(request.getSnapshotIds())
                    .build();
            BuiltResources built = microDomainResourceBuildService.buildResources(
                    buildRequest,
                    DeployMode.APPEND.equals(request.getMode()));
            try {
                microDomainService.deploy(built);
                return;
            } catch (KubeApiConflictException conflict) {
                if (attempt == MAX_CONFLICT_ATTEMPTS) {
                    throw conflict;
                }
                log.warn("Deploy of micro-domain '{}' lost a concurrency race on attempt {}/{}; "
                                + "rebuilding against current cluster state and retrying",
                        request.getName(), attempt, MAX_CONFLICT_ATTEMPTS);
            }
        }
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
