package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
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
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentSnapshotAction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

@Slf4j
@Service
public class BulkDeploymentService {
    /**
     * Attempts any single write sequence gets before a lost concurrency race is surfaced.
     */
    private static final int MAX_CONFLICT_ATTEMPTS = 3;

    private final MicroDomainResourceBuildService microDomainResourceBuildService;
    private final MicroDomainService microDomainService;
    private final ResourceBuildOptionsProvider resourceBuildOptionsProvider;
    private final DeploymentService deploymentService;
    private final ChainRepository chainRepository;
    private final EngineService engineService;
    private final DomainProperties domainProperties;

    @Autowired
    public BulkDeploymentService(
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

    @Transactional
    public void deployChains(DeployWithSnapshotCreationRequest request, Consumer<BulkDeploymentResponse> resultConsumer) {
        forClassicAndMicroDomains(request.getDomains(), (classicDomainNames, microDomainNames) -> {
            Collection<Snapshot> snapshots = getSnapshots(request.getChainIds(), request.getSnapshotAction(), resultConsumer);
            deploySnapshots(snapshots, classicDomainNames, microDomainNames, request.getMode(), resultConsumer);
        });
    }

    private Collection<Snapshot> getSnapshots(
        Collection<String> chainIds,
        BulkDeploymentSnapshotAction snapshotAction,
        Consumer<BulkDeploymentResponse> resultConsumer
    ) {
        Collection<Chain> chains = chainRepository.findAllById(chainIds).stream()
            .filter(chain -> {
                boolean isOverridden = nonNull(chain.getOverriddenByChainId());
                if (isOverridden) {
                    resultConsumer.accept(BulkDeploymentResponse.builder()
                        .chainId(chain.getId())
                        .chainName(chain.getName())
                        .status(BulkDeploymentStatus.IGNORED)
                        .build());
                }
                return !isOverridden;
            })
            .toList();
        return deploymentService.provideSnapshots(
                chains.stream().map(Chain::getId).toList(),
                snapshotAction,
                (chainId, msg) -> resultConsumer.accept(BulkDeploymentResponse.builder()
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
    }

    private void deploySnapshots(
        Collection<Snapshot> snapshots,
        Collection<String> classicDomainNames,
        Collection<String> microDomainNames,
        DeployMode mode,
        Consumer<BulkDeploymentResponse> resultConsumer
    ) {
        snapshots.stream()
            .map(snapshot -> deploymentService.deploySnapshot(
                snapshot, classicDomainNames))
            .flatMap(Collection::stream)
            .forEach(resultConsumer);

        microDomainNames.stream()
            .map(name -> {
                try {
                    deployResource(ResourceDeployRequest.builder()
                        .name(name)
                        .mode(mode)
                        .snapshotIds(snapshots.stream().map(Snapshot::getId).toList())
                        .build());
                    return buildResponseForSnapshots(snapshots, name, DomainType.MICRO,
                        BulkDeploymentStatus.CREATED, null);
                } catch (Exception e) {
                    return buildResponseForSnapshots(snapshots, name, DomainType.MICRO,
                        BulkDeploymentStatus.FAILED_DEPLOY, e.getMessage());
                }
            })
            .flatMap(Collection::stream)
            .forEach(resultConsumer);
    }

    public void deploySnapshots(
        Collection<Snapshot> snapshots,
        Collection<String> domains,
        DeployMode mode,
        Consumer<BulkDeploymentResponse> resultConsumer
    ) {
        forClassicAndMicroDomains(domains, (classicDomainNames, microDomainNames) ->
            deploySnapshots(snapshots, classicDomainNames, microDomainNames, mode, resultConsumer));
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
    public void deployResource(ResourceDeployRequest request) {
        for (int attempt = 1; ; attempt++) {
            ResourceBuildRequest buildRequest = ResourceBuildRequest.builder()
                .options(resourceBuildOptionsProvider.getOptions(request))
                .snapshotIds(request.getSnapshotIds())
                .build();
            MicroDomainService.BuiltResources built = microDomainResourceBuildService.buildResources(
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

    public Map<DomainType, List<String>> groupDomainsByType(Collection<String> domainNames) {
        Map<String, DomainType> domainTypeMap = engineService.getDomains().stream()
            .collect(Collectors.toMap(
                EngineDomain::getName,
                EngineDomain::getType
            ));
        return domainNames
            .stream()
            .collect(Collectors.groupingBy(
                name -> domainTypeMap.getOrDefault(name, DomainType.MICRO)));
    }

    public void forClassicAndMicroDomains(
        Collection<String> domainNames,
        BiConsumer<Collection<String>, Collection<String>> consumer
    ) {
        Map<DomainType, List<String>> domainByType = groupDomainsByType(domainNames);

        Collection<String> classicDomainNames = domainByType.getOrDefault(DomainType.CLASSIC, Collections.emptyList());
        if (!domainProperties.getClassic().isEnabled() && !classicDomainNames.isEmpty()) {
            throw new DomainTypeDisabledException(DomainType.CLASSIC);
        }

        Collection<String> microDomainNames = domainByType.getOrDefault(DomainType.MICRO, Collections.emptyList());
        if (!domainProperties.getMicro().isEnabled() && !microDomainNames.isEmpty()) {
            throw new DomainTypeDisabledException(DomainType.MICRO);
        }

        consumer.accept(classicDomainNames, microDomainNames);
    }

    public List<BulkDeploymentResponse> buildResponseForSnapshots(
        Collection<Snapshot> snapshots,
        String domainName,
        DomainType domainType,
        BulkDeploymentStatus status,
        String errorMessage
    ) {
        EngineDomain domain = EngineDomain.builder()
            .name(domainName)
            .type(domainType)
            .build();
        return snapshots.stream()
            .map(snapshot -> BulkDeploymentResponse.builder()
                .chainId(snapshot.getChain().getId())
                .chainName(snapshot.getChain().getName())
                .status(status)
                .errorMessage(errorMessage)
                .domain(domain)
                .build())
            .toList();
    }
}
