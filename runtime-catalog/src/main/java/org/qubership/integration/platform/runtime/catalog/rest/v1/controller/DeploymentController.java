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
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping(value = {
    "/v1/catalog/chains/{chainId}/deployments",
    "/api/v1/cip/catalog/chains/{chainId}/deployments"}, produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "deployment-controller", description = "Deployment Controller")
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final DeploymentMapper deploymentMapper;
    private final RuntimeDeploymentService runtimeDeploymentService;
    private final DomainProperties domainProperties;
    private final BulkDeploymentService bulkDeploymentService;
    private final SnapshotService snapshotService;
    private final ChainFinderService chainFinderService;

    @Autowired
    public DeploymentController(
            DeploymentService deploymentService,
            DeploymentMapper deploymentMapper,
            RuntimeDeploymentService runtimeDeploymentService,
            DomainProperties domainProperties,
            BulkDeploymentService bulkDeploymentService,
            SnapshotService snapshotService,
            ChainFinderService chainFinderService
    ) {
        this.deploymentService = deploymentService;
        this.deploymentMapper = deploymentMapper;
        this.runtimeDeploymentService = runtimeDeploymentService;
        this.domainProperties = domainProperties;
        this.bulkDeploymentService = bulkDeploymentService;
        this.snapshotService = snapshotService;
        this.chainFinderService = chainFinderService;
    }

    @GetMapping
    @Operation(description = "Get all deployments for specified chain")
    public ResponseEntity<List<DeploymentResponse>> findAllByChainId(@PathVariable @Parameter(description = "Chain id") String chainId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find all required deployment states of chain: {}", chainId);
        }
        List<DeploymentResponse> response = new ArrayList<>();
        if (domainProperties.getClassic().isEnabled()) {
            deploymentService.findAllByChainId(chainId).stream()
                    .map(deployment -> {
                        RuntimeDeployment runtimeState = runtimeDeploymentService.getRuntimeDeployment(deployment.getId());
                        return deploymentMapper.asResponse(deployment, runtimeState);
                    }).forEach(response::add);
        }
        if (domainProperties.getMicro().isEnabled()) {
            response.addAll(runtimeDeploymentService.getMicroEngineDeployments(chainId));
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{deploymentId}")
    @Operation(description = "Get particular deployment of the chain")
    public ResponseEntity<DeploymentResponse> findById(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                       @PathVariable @Parameter(description = "Deployment id") String deploymentId) {
        log.debug("Request to find required deployment {} state in chain {}", chainId, deploymentId);
        return verifyClassicDomainEnabled(() -> {
            Deployment deployment = deploymentService.findById(deploymentId);
            RuntimeDeployment runtimeState = runtimeDeploymentService.getRuntimeDeployment(deploymentId);
            var response = deploymentMapper.asResponse(deployment, runtimeState);
            return ResponseEntity.ok(response);
        });
    }

    @PostMapping
    @Operation(description = "Create deployment for the chain")
    public ResponseEntity<DeploymentResponse> create(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                     @RequestBody @Valid @Parameter(description = "Deployment request object") DeploymentRequest request) {
        log.info("Request to create new deployment in chain: {}", chainId);
        Optional<DomainType> discovered = bulkDeploymentService.discoverDomainType(request.getDomain());
        DomainType declared = request.getDomainType();
        if (discovered.isPresent() && declared != null && discovered.get() != declared) {
            throw new DeploymentProcessingException(String.format(
                    "Domain %s is discovered as %s, the request declares %s",
                    request.getDomain(), discovered.get(), declared));
        }
        if (declared == DomainType.MICRO || discovered.filter(type -> type == DomainType.MICRO).isPresent()) {
            return verifyMicroDomainEnabled(() -> {
                Chain chain = chainFinderService.findById(chainId);
                Snapshot snapshot = snapshotService.findById(request.getSnapshotId());
                if (!chain.getId().equals(snapshot.getChain().getId())) {
                    throw new EntityNotFoundException(
                            "Snapshot with id " + snapshot.getId() + " not found in chain " + chain.getId());
                }
                bulkDeploymentService.deployResource(ResourceDeployRequest.builder()
                        .name(request.getDomain())
                        .snapshotIds(List.of(snapshot.getId()))
                        .mode(request.getMode())
                        .build());
                return ResponseEntity.ok(deploymentMapper.asMicroResponse(chain.getId(), request.getDomain(), snapshot));
            });
        }
        return verifyClassicDomainEnabled(() -> {
            String snapshotId = request.getSnapshotId();
            Deployment deployment = deploymentMapper.asEntity(request);
            deployment = deploymentService.create(deployment, chainId, snapshotId);
            DeploymentResponse response = deploymentMapper.asResponse(deployment);
            return ResponseEntity.ok(response);
        });
    }

    @PostMapping("/all")
    @Operation(description = "Bulk create deployment for the chain")
    public ResponseEntity<List<DeploymentResponse>> createAll(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                     @RequestBody @Valid @Parameter(description = "List of deployment request objects") List<DeploymentRequest> request) {
        log.info("Request to create new deployments in chain: {}", chainId);
        return verifyClassicDomainEnabled(() -> {
            List<Deployment> deployments = deploymentService.createAll(deploymentMapper.asEntities(request), chainId);
            return ResponseEntity.ok(deploymentMapper.asResponses(deployments));
        });
    }

    @DeleteMapping
    @Operation(description = "Delete all deployments for specified chain")
    public ResponseEntity<Void> deleteByChainId(@PathVariable @Parameter(description = "Chain id") String chainId) {
        log.info("Request to delete all deployments of chain: {}", chainId);
        return verifyClassicDomainEnabled(() -> {
            deploymentService.deleteAllByChainId(chainId);
            return ResponseEntity.noContent().build();
        });
    }

    @DeleteMapping("/{deploymentId}")
    @Operation(description = "Delete specific deployment for the chain")
    public ResponseEntity<Void> deleteById(@PathVariable @Parameter(description = "Chain id") String chainId,
                                        @PathVariable @Parameter(description = "Deployment id") String deploymentId) {
        log.info("Request to delete deployment {} from chain {}", deploymentId, chainId);
        if (domainProperties.getClassic().isEnabled()) {
            Optional<Deployment> classicDeployment = deploymentService.tryFindById(deploymentId);
            if (classicDeployment.isPresent()) {
                deploymentService.deleteById(deploymentId);
                return ResponseEntity.noContent().build();
            }
        }
        if (domainProperties.getMicro().isEnabled()) {
            Optional<MicroDeployment> microDeployment = resolveMicroDeployment(chainId, deploymentId);
            if (microDeployment.isPresent()) {
                bulkDeploymentService.deleteChainSnapshot(microDeployment.get().domain(), microDeployment.get().snapshotId());
                return ResponseEntity.noContent().build();
            }
        }
        if (!domainProperties.getClassic().isEnabled()) {
            throw new DomainTypeDisabledException(DomainType.CLASSIC);
        }
        throw new EntityNotFoundException("Deployment with id " + deploymentId + " not found");
    }

    private <T> T verifyClassicDomainEnabled(Supplier<T> supplier) {
        if (domainProperties.getClassic().isEnabled()) {
            return supplier.get();
        } else {
            throw new DomainTypeDisabledException(DomainType.CLASSIC);
        }
    }

    private <T> T verifyMicroDomainEnabled(Supplier<T> supplier) {
        if (domainProperties.getMicro().isEnabled()) {
            return supplier.get();
        } else {
            throw new DomainTypeDisabledException(DomainType.MICRO);
        }
    }

    private Optional<MicroDeployment> resolveMicroDeployment(String chainId, String deploymentId) {
        Optional<MicroDeployment> parsed = parseMicroDeployment(chainId, deploymentId);
        if (parsed.isPresent()) {
            return parsed;
        }
        return runtimeDeploymentService.getMicroEngineDeployments(chainId).stream()
                .filter(deployment -> deploymentId.equals(deployment.getId()))
                .findFirst()
                .map(deployment -> new MicroDeployment(deployment.getDomain(), deployment.getSnapshotId()));
    }

    private Optional<MicroDeployment> parseMicroDeployment(String chainId, String deploymentId) {
        return snapshotService.findByChainIdLight(chainId).stream()
                .map(Snapshot::getId)
                .filter(snapshotId -> deploymentId.length() > snapshotId.length() + 1)
                .filter(snapshotId -> deploymentId.endsWith("-" + snapshotId))
                .max(Comparator.comparingInt(String::length))
                .map(snapshotId -> new MicroDeployment(
                        deploymentId.substring(0, deploymentId.length() - snapshotId.length() - 1),
                        snapshotId));
    }

    private record MicroDeployment(String domain, String snapshotId) {
    }
}
