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

package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.configuration.aspect.DeploymentModification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.model.ElementRoute;
import org.qubership.integration.platform.runtime.catalog.model.MultiConsumer;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineDeploymentsDTO;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentsUpdate;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.DeploymentRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentSnapshotAction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.event.GenericMessageType;
import org.qubership.integration.platform.runtime.catalog.service.deployment.DeploymentBuilderService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.util.TriggerUtils.*;

@Slf4j
@Service
public class DeploymentService {

    private static final String DEPLOYMENT_WITH_ID_NOT_FOUND_MESSAGE = "Can't find deployment with id: ";

    private final DeploymentRepository deploymentRepository;
    private final ElementRepository elementRepository;

    private final ChainFinderService chainFinderService;
    private final SnapshotService snapshotService;
    private final ActionsLogService actionLogger;
    private final DeploymentBuilderService deploymentBuilderService;
    private final TransactionHandler transactionHandler;
    private final RoutesGetterService routesGetterService;

    @Value("${qip.chains.triggers.check.enabled}")
    private boolean triggersCheckEnabled;

    @Value("${qip.control-plane.chain-routes-registration.egress-gateway:true}")
    private boolean registerOnEgress;

    @Value("${qip.control-plane.chain-routes-registration.ingress-gateways:true}")
    private boolean registerOnIncomingGateways;

    // <id, userId, message, type, optionalFields>
    private MultiConsumer.Consumer5<String, String, String, GenericMessageType, Map<String, String>> messagesCallback = (a, b, c, d, e) -> {
    };

    @SuppressWarnings("checkstyle:ConstantName")
    private static final Map<String, DeploymentsUpdate> fullDeploymentsUpdateCache = new ConcurrentHashMap<>();
    private static Long deploymentsUpdateVersion = 0L;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoggingInfo {
        public static LoggingInfo EMPTY_INFO = new LoggingInfo();

        private Long createdWhen;

        public LoggingInfo(Optional<Deployment> deploymentOptional) {
            if (deploymentOptional.isPresent()) {
                Deployment deployment = deploymentOptional.get();
                createdWhen = deployment.getCreatedWhen().getTime();
            }
        }
    }


    @Autowired
    public DeploymentService(DeploymentRepository deploymentRepository,
                             ElementRepository elementRepository,
                             ChainFinderService chainFinderService,
                             SnapshotService snapshotService,
                             ActionsLogService actionLogger,
                             DeploymentBuilderService deploymentBuilderService,
                             TransactionHandler transactionHandler,
                             RoutesGetterService routesGetterService
    ) {
        this.deploymentRepository = deploymentRepository;
        this.elementRepository = elementRepository;
        this.chainFinderService = chainFinderService;
        this.snapshotService = snapshotService;
        this.actionLogger = actionLogger;
        this.deploymentBuilderService = deploymentBuilderService;
        this.transactionHandler = transactionHandler;
        this.routesGetterService = routesGetterService;
    }

    @Transactional
    public Deployment findById(String deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(DEPLOYMENT_WITH_ID_NOT_FOUND_MESSAGE + deploymentId));
    }

    @Transactional
    public List<Deployment> findAllByChainId(String chainId) {
        return deploymentRepository.findAllByChainId(chainId);
    }

    @Transactional
    public long getDeploymentsCountByDomain(String domainName) {
        return deploymentRepository.countByDomain(domainName);
    }

    @DeploymentModification
    public List<Deployment> createAll(List<Deployment> deployments, String chainId) {
        if (!deployments.isEmpty()) {
            return createAll(deployments, chainId, deployments.get(0).getSnapshot());
        }
        return Collections.emptyList();
    }

    /**
     * Create deployments from the same snapshot with external triggers checking
     *
     * @param deployments with different domains
     * @param chainId
     * @return list of created deployments
     */
    @DeploymentModification
    public List<Deployment> createAll(List<Deployment> deployments, String chainId, Snapshot snapshot) {
        if (!checkTriggersInBulkDeploy(deployments)) {
            throw new DeploymentProcessingException("Found external or private triggers while deploying to multiple domains");
        }

        List<Deployment> createdDeployments = new ArrayList<>(deployments.size());
        for (Deployment deployment : deployments) {
            Deployment createdDeployment = create(deployment, chainId, snapshot);
            if (createdDeployment != null) {
                createdDeployments.add(createdDeployment);
            }
        }

        return createdDeployments;
    }

    @DeploymentModification
    public Deployment create(Deployment deployment, String chainId, Snapshot snapshot) {
        return create(deployment, chainFinderService.findById(chainId), snapshot, null);
    }

    @DeploymentModification
    public Deployment create(Deployment deployment, String chainId, String snapshotId) {
        return create(deployment, chainFinderService.findById(chainId), snapshotService.findById(snapshotId), null);
    }

    @DeploymentModification
    public Deployment create(Deployment deployment, Chain chain, Snapshot snapshot, List<Deployment> excludeDeployments) {
        if (log.isDebugEnabled()) {
            log.debug("Request to create deployment for chain {}, snapshot {}", chain.getId(), snapshot.getId());
        }

        AtomicReference<Deployment> savedDeployment = new AtomicReference<>();


        transactionHandler.runInNewTransaction(() -> {
            checkTriggers(deployment.getDomain(), snapshot.getId(), chain.getId(), excludeDeployments);
            prepareDeployment(deployment, snapshot, chain);
            savedDeployment.set(deploymentRepository.save(deployment));
            logDeploymentAction(savedDeployment.get(), chain.getId(), chain.getName(), LogOperation.CREATE);
        });
        return savedDeployment.get();
    }

    public Map<String, Snapshot> provideSnapshots(
            Collection<String> chainIds,
            BulkDeploymentSnapshotAction action,
            BiConsumer<String, String> errorHandler
    ) {
        return switch (action) {
            case CREATE_NEW -> snapshotService.buildAll(chainIds, errorHandler);
            case LAST_CREATED -> snapshotService.findLastCreatedOrBuild(chainIds, errorHandler);
        };
    }

    @DeploymentModification
    public List<BulkDeploymentResponse> deploySnapshot(Snapshot snapshot, Collection<String> domains) {
        if (domains.size() > 1 && !checkTriggersInBulkDeploy(snapshot)) {
            Chain chain = snapshot.getChain();
            String chainId = chain.getId();
            String chainName = chain.getName();
            return domains.stream().map(name -> BulkDeploymentResponse.builder()
                    .chainId(chainId)
                    .chainName(chainName)
                    .status(BulkDeploymentStatus.FAILED_DEPLOY)
                    .errorMessage("Found external or private triggers while deploying to multiple domains")
                    .domain(EngineDomain.builder()
                            .name(name)
                            .type(DomainType.CLASSIC)
                            .build())
                    .build()).toList();
        }

        return domains.stream()
                .map(name -> deploySnapshotToDomain(snapshot, name))
                .toList();
    }

    @DeploymentModification
    public BulkDeploymentResponse deploySnapshotToDomain(Snapshot snapshot, String domainName) {
        Deployment deployment = new Deployment();
        deployment.setDomain(domainName);

        Chain chain = snapshot.getChain();
        String chainId = chain.getId();
        String chainName = chain.getName();

        BulkDeploymentResponse.BulkDeploymentResponseBuilder responseBuilder = BulkDeploymentResponse.builder()
                .chainId(chainId)
                .chainName(chainName)
                .domain(EngineDomain.builder()
                        .name(deployment.getDomain())
                        .type(DomainType.CLASSIC)
                        .build());

        try {
            create(deployment, chainId, snapshot);
            return responseBuilder
                    .status(BulkDeploymentStatus.CREATED)
                    .build();
        } catch (Exception e) {
            log.error("Error creating deployment for chain: {}, {}", snapshot.getChain().getId(), e.getMessage());
            return responseBuilder
                    .status(BulkDeploymentStatus.FAILED_DEPLOY)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Transactional
    public Pair<Boolean, List<BulkDeploymentResponse>> bulkCreate(BulkDeploymentRequest request) {
        final AtomicReference<Boolean> failed = new AtomicReference<>(false);
        List<BulkDeploymentResponse> statuses = new ArrayList<>();

        final Map<String, Chain> chains = (CollectionUtils.isEmpty(request.getChainIds())
                ? chainFinderService.findAll()
                : chainFinderService.findAllById(request.getChainIds())).stream()
                .filter(chain -> {
                    if (chain.getOverriddenByChainId() != null) {
                        statuses.add(BulkDeploymentResponse.builder()
                                .chainId(chain.getId())
                                .chainName(chain.getName())
                                .status(BulkDeploymentStatus.IGNORED)
                                .build());
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toMap(AbstractEntity::getId, Function.identity()));

        log.info("Bulk deploy for {} chains", chains.size());

        BiConsumer<String, String> errorHandler = (chainId, msg) -> {
            statuses.add(BulkDeploymentResponse.builder()
                    .chainId(chainId)
                    .chainName(chains.get(chainId).getName())
                    .status(BulkDeploymentStatus.FAILED_SNAPSHOT)
                    .errorMessage(msg)
                    .build());
            failed.set(true);
        };

        provideSnapshots(chains.keySet(), request.getSnapshotAction(), errorHandler)
                .values()
                .stream()
                .map(snapshot -> deploySnapshot(snapshot, request.getDomains()))
                .flatMap(Collection::stream)
                .peek(result -> failed.compareAndSet(
                        false, BulkDeploymentStatus.FAILED_DEPLOY.equals(result.getStatus())))
                .forEach(statuses::add);
        return Pair.of(failed.get(), statuses);
    }

    private void prepareDeployment(Deployment deployment, Snapshot snapshot, Chain chain) {
        deployment.setSnapshot(snapshot);
        deployment.setChain(chain);
        deployment.setName(snapshot.getName());

        deployment.setDeploymentRoutes(buildDeploymentRoutes(deployment));
    }

    private List<DeploymentRoute> buildDeploymentRoutes(Deployment deployment) {
        String snapshotId = deployment.getSnapshot().getId();
        Specification<ChainElement> specification = (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("snapshot").get("id"), snapshotId);
        return routesGetterService.getRoutes(specification);
    }

    public boolean checkRouteExists(ElementRoute route, String excludeChainId) {
        return findElementsThatUseRoute(route, excludeChainId).findAny().isPresent();
    }

    public List<Pair<String, Deployment>> findRouteDeployments(ElementRoute route, String excludeChainId) {
        return findElementsThatUseRoute(route, excludeChainId)
                .flatMap(element -> {
                    String path = getHttpTriggerPath(element);
                    return element.getSnapshot().getDeployments().stream().map(deployment -> Pair.of(path, deployment));
                })
                .collect(Collectors.toList());
    }

    private Stream<ChainElement> findElementsThatUseRoute(ElementRoute route, String excludeChainId) {
        return elementRepository
                .findElementsForRouteExistenceCheck(List.of(CamelNames.HTTP_TRIGGER_COMPONENT), excludeChainId)
                .stream()
                .filter(element -> getHttpTriggerRoute(element).intersectsWith(route));
    }

    private void checkTriggers(String domain, String snapshotId, String chainId, List<Deployment> excludeDeployments) {
        if (!triggersCheckEnabled) {
            return;
        }

        List<String> excludeDeploymentIds = excludeDeployments == null
                ? null
                : excludeDeployments.stream().map(Deployment::getId).collect(Collectors.toList());

        checkHttpTriggers(snapshotId, chainId, excludeDeploymentIds, domain);

        checkSdsTriggers(snapshotId, chainId, excludeDeploymentIds, domain);

    }

    private void checkSdsTriggers(String snapshotId, String chainId, List<String> excludeDeploymentIds, String domain) {
        List<String> triggersToCheck = List.of(CamelNames.SDS_TRIGGER_COMPONENT);
        List<String> pendingJobIds = mapSdsTriggerJobIds(
                elementRepository.findAllBySnapshotIdAndTypeIn(
                        snapshotId,
                        triggersToCheck));
        if (pendingJobIds.isEmpty()) {
            return;
        }

        List<String> domainJobIds = mapSdsTriggerJobIds(
                elementRepository.findElementsForDomainTriggerCheck(
                        triggersToCheck,
                        domain,
                        chainId,
                        SQLUtils.prepareCollectionForHqlNotInClause(excludeDeploymentIds)));

        Set<String> domainEqualJobIds = findSameSdsTriggerJobIds(pendingJobIds, domainJobIds);
        if (!domainEqualJobIds.isEmpty()) {
            throw new EntityExistsException("Found similar Job Ids registered on scheduling-service (SDS) on the same domain: "
                    + domainEqualJobIds);
        }
    }

    private void checkHttpTriggers(String snapshotId, String chainId, List<String> excludeDeploymentIds, String domain) {
        List<String> triggersToCheck = List.of(CamelNames.HTTP_TRIGGER_COMPONENT);

        List<ElementRoute> pendingRoutes = mapHttpTriggerRoutes(
                elementRepository.findAllBySnapshotIdAndTypeIn(
                        snapshotId,
                        triggersToCheck));
        if (pendingRoutes.isEmpty()) {
            return;
        }

        List<ElementRoute> allRoutes = mapHttpTriggerRoutes(
                elementRepository.findElementsForTriggerCheck(
                        triggersToCheck,
                        chainId,
                        SQLUtils.prepareCollectionForHqlNotInClause(excludeDeploymentIds)));

        List<ElementRoute> otherDomainsRoutes = mapHttpTriggerRoutes(
                elementRepository.findElementsForDomainTriggerCheck(
                        triggersToCheck,
                        domain,
                        chainId,
                        SQLUtils.prepareCollectionForHqlNotInClause(excludeDeploymentIds)));

        Set<String> gatewayEqualPaths = findSameHttpTriggerPaths(pendingRoutes, allRoutes, true);
        Set<String> otherDomainsEqualPaths = findSameHttpTriggerPaths(pendingRoutes, otherDomainsRoutes, false);

        if (!gatewayEqualPaths.isEmpty()) {
            throw new EntityExistsException("Found similar triggers paths registered on public/private gateway: "
                    + gatewayEqualPaths);
        }
        if (!otherDomainsEqualPaths.isEmpty()) {
            throw new EntityExistsException("Found similar triggers path registered on other domains: " + otherDomainsEqualPaths);
        }
    }

    private boolean checkTriggersInBulkDeploy(List<Deployment> deployments) {
        return deployments.size() < 2 || checkTriggersInBulkDeploy(deployments.get(0).getSnapshot());
    }

    private boolean checkTriggersInBulkDeploy(Snapshot snapshot) {
        String snapshotId = snapshot.getId();
        List<ChainElement> triggers =
                elementRepository.findAllBySnapshotIdAndType(snapshotId, getHttpTriggerTypeName());

        return triggers.stream().noneMatch(trigger ->
                TriggerUtils.isExternalHttpTrigger(trigger) || TriggerUtils.isPrivateHttpTrigger(trigger));
    }

    private List<ElementRoute> mapHttpTriggerRoutes(Collection<ChainElement> listOfObjects) {
        return listOfObjects.stream().map(TriggerUtils::getHttpTriggerRoute).toList();
    }

    private List<String> mapSdsTriggerJobIds(Collection<ChainElement> listOfObjects) {
        return listOfObjects.stream().map(TriggerUtils::getSdsTriggerJobId).toList();
    }

    private Set<String> findSameHttpTriggerPaths(List<ElementRoute> pendingRoutes, List<ElementRoute> existingRoutes, boolean checkGatewayOnly) {
        Set<String> equalPaths = new HashSet<>();

        Map<String, Set<HttpMethod>> pendingPathIntersection = new HashMap<>();
        for (ElementRoute route : pendingRoutes) {
            if (StringUtils.isNotBlank(route.getPath()) && (!checkGatewayOnly || route.isExternal() || route.isPrivate())) {
                Set<HttpMethod> intersectionMethods = pendingPathIntersection.get(route.getPath());
                if (intersectionMethods != null) {
                    if (route.getMethods().stream().anyMatch(intersectionMethods::contains)) {
                        equalPaths.add(route.getPath());
                    }
                } else {
                    pendingPathIntersection.put(route.getPath(), route.getMethods());
                }
            }
        }

        for (ElementRoute route : existingRoutes) {
            if (StringUtils.isNotBlank(route.getPath()) && (!checkGatewayOnly || route.isExternal() || route.isPrivate())) {
                Set<HttpMethod> intersectionMethods = pendingPathIntersection.get(route.getPath());
                if (intersectionMethods != null) {
                    if (route.getMethods().stream().anyMatch(intersectionMethods::contains)) {
                        equalPaths.add(route.getPath());
                    }
                }
            }
        }

        return equalPaths;
    }

    private Set<String> findSameSdsTriggerJobIds(List<String> pendingJobIds, List<String> domainJobIds) {
        return pendingJobIds.stream().filter(domainJobIds::contains).collect(Collectors.toSet());
    }

    @DeploymentModification
    public void deleteAllByChainId(String chainId) throws DeploymentProcessingException {
        transactionHandler.runInNewTransaction(() -> {
            List<Deployment> deployments = findAllByChainId(chainId);
            deploymentRepository.deleteAllByChainId(chainId);
            deployments.forEach(deployment -> logDeploymentAction(deployment, deployment.getId(), deployment.getChain().getName(), LogOperation.DELETE));
        });
    }

    @DeploymentModification
    public void deleteAllBySnapshotId(String snapshotId) throws DeploymentProcessingException {
        Snapshot snapshot = snapshotService.findById(snapshotId);
        snapshot.getDeployments().forEach(deployment -> logDeploymentAction(deployment, deployment.getId(), deployment.getChain().getName(), LogOperation.DELETE));
        transactionHandler.runInNewTransaction(() -> deploymentRepository.deleteAllBySnapshotId(snapshotId));
    }

    @DeploymentModification
    public void deleteById(String deploymentId) throws DeploymentProcessingException {
        transactionHandler.runInNewTransaction(() -> {
            Deployment deployment = deploymentRepository.findById(deploymentId).orElseThrow(() -> new EntityNotFoundException(DEPLOYMENT_WITH_ID_NOT_FOUND_MESSAGE + deploymentId));

            deploymentRepository.deleteById(deploymentId);

            logDeploymentAction(deployment, deployment.getChain().getId(), deployment.getChain().getName(), LogOperation.DELETE);
        });
    }

    private void logDeploymentAction(Deployment deployment, String parentId, String parentName, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.DEPLOYMENT)
                .entityId(deployment.getId())
                .entityName(deployment.getDomain())
                .parentType(parentId == null ? null : EntityType.CHAIN)
                .parentId(parentId)
                .parentName(parentName)
                .operation(operation)
                .build());
    }


    @Transactional(propagation = Propagation.NEVER)
    public DeploymentsUpdate getDeploymentsForDomain(String domainName, EngineDeploymentsDTO engineDeployments) {
        final List<DeploymentUpdate> update = new ArrayList<>();
        final List<DeploymentUpdate> stop = new ArrayList<>();
        boolean fullDeploymentsRequest = engineDeployments.getExcludeDeployments().isEmpty();
        Long currentDeploymentVersion = deploymentsUpdateVersion;
        if (fullDeploymentsRequest) {
            DeploymentsUpdate initialUpdate = fullDeploymentsUpdateCache.get(domainName);
            if (initialUpdate != null) {
                return initialUpdate;
            }
        }

        List<DeploymentInfo> excludeDeployments = engineDeployments.getExcludeDeployments();

        if (CollectionUtils.isEmpty(excludeDeployments)) {
            update.addAll(deploymentBuilderService.buildDeploymentsUpdate(deploymentRepository.findAllByDomain(domainName)));
        } else { // calculate delta
            List<String> toExcludeIds = excludeDeployments.stream().map(DeploymentInfo::getDeploymentId).toList();

            List<Deployment> toUpdate = deploymentRepository.findDeploymentsToUpdate(domainName, toExcludeIds);
            update.addAll(deploymentBuilderService.buildDeploymentsUpdate(toUpdate));

            Set<String> toRemoveIds = deploymentRepository.findDeploymentsToRemove(domainName, toExcludeIds);
            List<DeploymentInfo> toRemove = excludeDeployments.stream()
                    .filter(ex -> toRemoveIds.contains(ex.getDeploymentId()))
                    .toList();

            stop.addAll(deploymentBuilderService.buildDeploymentsStop(toRemove));
        }

        DeploymentsUpdate result = DeploymentsUpdate.builder().update(update).stop(stop).build();
        if (fullDeploymentsRequest) {
            if (Objects.equals(currentDeploymentVersion, deploymentsUpdateVersion)) {
                fullDeploymentsUpdateCache.put(domainName, result);
            }
        }
        return result;
    }

    public void subscribeMessages(MultiConsumer.Consumer5<String, String, String, GenericMessageType, Map<String, String>> messagesCallback) {
        this.messagesCallback = messagesCallback;
    }

    public static void clearDeploymentsUpdateCache(Long version) {
        fullDeploymentsUpdateCache.clear();
        deploymentsUpdateVersion = version;
    }
}
