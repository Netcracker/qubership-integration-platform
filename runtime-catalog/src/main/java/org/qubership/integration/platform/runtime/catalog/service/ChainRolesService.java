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

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.AbacRoleChangeException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.ChainRuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.DeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.runtime.catalog.model.filter.ChainElementFilterColumn;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementFilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementSearchCriteria;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainRolesMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;


@Slf4j
@Service
public class ChainRolesService {
    private static final String ROLES = "roles";
    private static final String ACCESS_CONTROL_TYPE = "accessControlType";
    public static final String ACCESS_CONTROL_TYPE_ABAC = "ABAC";
    public static final String ACCESS_CONTROL_TYPE_NONE = "NONE";
    public static final String ACCESS_CONTROL_TYPE_RBAC = "RBAC";
    private final ElementService elementService;
    private final DeploymentService deploymentService;
    private final RuntimeDeploymentService runtimeDeploymentService;
    private final ChainRolesMapper chainRolesMapper;
    private final ChainService chainService;
    private final ChainFinderService chainFinderService;
    private final ElementRepository elementRepository;
    private final DeploymentMapper deploymentMapper;
    private final SnapshotService snapshotService;
    private final ActionsLogService actionLogger;

    public ChainRolesService(ElementService elementService,
                             DeploymentService deploymentService,
                             RuntimeDeploymentService runtimeDeploymentService,
                             ChainRolesMapper chainRolesMapper, ChainService chainService,
                             ChainFinderService chainFinderService,
                             ElementRepository elementRepository,
                             DeploymentMapper deploymentMapper,
                             SnapshotService snapshotService, ActionsLogService actionLogger) {
        this.elementService = elementService;
        this.deploymentService = deploymentService;
        this.runtimeDeploymentService = runtimeDeploymentService;
        this.chainRolesMapper = chainRolesMapper;
        this.chainService = chainService;
        this.chainFinderService = chainFinderService;
        this.elementRepository = elementRepository;
        this.deploymentMapper = deploymentMapper;
        this.snapshotService = snapshotService;
        this.actionLogger = actionLogger;
    }

    public ChainRolesResponse findAllChainByHttpTrigger(ChainElementSearchCriteria request, boolean isImplementedOnly) {
        int offset = request.getOffset();
        int limit = request.getLimit();
        List<ChainElementFilterRequestDTO> filters = request.getFilters();
        if (offset < 0 || limit < 1) {
            return new ChainRolesResponse(0, Collections.emptyList());
        }

        List<ChainElement> elementList = elementRepository.findElementsByFilter(offset, limit, List.of(CamelNames.HTTP_TRIGGER_COMPONENT), filters, isImplementedOnly);
        List<ChainRolesDTO> chainRolesResponse = chainRolesMapper.asChainRolesResponses(elementList);

        if (!chainRolesResponse.isEmpty()) {
            Map<String, Collection<ChainRuntimeDeployment>> runtimeDeployments = runtimeDeploymentService.getChainRuntimeDeployments();
            setDeploymentStatuses(chainRolesResponse, runtimeDeployments);
            chainRolesResponse = getChainsFilteredByStatus(chainRolesResponse, filters);
        }

        return new ChainRolesResponse(offset + chainRolesResponse.size(), chainRolesResponse);
    }

    /** Applies the roles to every element of the batch, or to none of them. */
    @Transactional
    public void updateRoles(List<UpdateRolesRequest> request) {
        resolveUpdates(request).forEach(update -> applyRoles(update.element(), update.roles()));
    }

    /**
     * An id that names no chain deploys nothing. Past that point the batch runs to the end: a chain
     * that fails keeps its roles as unsaved changes and is named in the error.
     */
    public void redeploy(List<String> chainIds) {
        List<Chain> chains = chainIds.stream()
                .distinct()
                .map(chainFinderService::findById)
                .toList();

        List<RuntimeException> failures = new ArrayList<>();
        for (Chain chain : chains) {
            try {
                redeployChain(chain);
            } catch (RuntimeException exception) {
                // Only the message survives into the aggregate error, so keep the trace here.
                log.error("Unable to redeploy chain {}", chain.getId(), exception);
                failures.add(exception);
            }
        }
        reportRedeployFailures(failures, chains.size());
    }

    private record RoleUpdate(ChainElement element, Set<String> roles) {
    }

    private List<RoleUpdate> resolveUpdates(List<UpdateRolesRequest> request) {
        List<RoleUpdate> updates = new ArrayList<>(request.size());
        for (UpdateRolesRequest updateRequest : request) {
            ChainElement element = elementService.findById(updateRequest.getElementId());
            if (ACCESS_CONTROL_TYPE_ABAC.equals(element.getPropertyAsString(ACCESS_CONTROL_TYPE))) {
                throw new AbacRoleChangeException(element.getId());
            }
            updates.add(new RoleUpdate(element, updateRequest.getRoles()));
        }
        return updates;
    }

    private void applyRoles(ChainElement element, Set<String> roles) {
        List<String> newRoles = Lists.newArrayList(roles);
        String accessControlType = element.getPropertyAsString(ACCESS_CONTROL_TYPE);

        if (ACCESS_CONTROL_TYPE_NONE.equals(accessControlType) && !newRoles.isEmpty()) {
            element.getProperties().put(ACCESS_CONTROL_TYPE, ACCESS_CONTROL_TYPE_RBAC);
        }

        if (ACCESS_CONTROL_TYPE_RBAC.equals(accessControlType) && newRoles.isEmpty()) {
            element.getProperties().put(ACCESS_CONTROL_TYPE, ACCESS_CONTROL_TYPE_NONE);
        }

        element.getProperties().put(ROLES, newRoles);
        element.getChain().setUnsavedChanges(true);
        elementService.save(element);

        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.ELEMENT)
                .entityId(element.getId())
                .entityName(element.getName())
                .parentType(EntityType.CHAIN)
                .parentId(element.getChain().getId())
                .parentName(element.getChain().getName())
                .operation(LogOperation.UPDATE)
                .build());
    }

    private void redeployChain(Chain chain) {
        String chainId = chain.getId();
        try {
            List<Deployment> deployments = chain.getDeployments();
            List<DeploymentRequest> deploymentRequestLst = new ArrayList<>();
            Snapshot snapshot = snapshotService.build(chainId);
            if (deployments.isEmpty()) {
                DeploymentRequest deploymentRequest = chainRolesMapper.prepareDeploymentRequest(snapshot);
                deploymentRequestLst.add(deploymentRequest);
            } else {
                deployments.get(0).setSnapshot(snapshot);
                deploymentRequestLst = chainRolesMapper.prepareDeploymentRequest(deployments);
            }
            deploymentService.createAll(deploymentMapper.asEntities(deploymentRequestLst), chainId, snapshot);
            chain.setUnsavedChanges(false);
            chain.setCurrentSnapshot(snapshot);
            chainService.update(chain);
        } catch (SnapshotCreationException exception) {
            ChainElement exceptionChainElement = chain.getElements()
                    .stream()
                    .filter(chainElement -> chainElement.getId().equals(exception.getElementId()))
                    .findFirst()
                    .orElse(null);
            throw new SnapshotCreationException("Unable to create snapshot for chain " + chainId + " :" + exception.getMessage(),
                    chainId,
                    exceptionChainElement,
                    exception
            );
        } catch (Exception exception) {
            throw new DeploymentProcessingException("Unable to redeploy chain " + chainId + ":" + exception.getMessage(), exception);
        }
    }

    private void reportRedeployFailures(List<RuntimeException> failures, int chainCount) {
        if (failures.isEmpty()) {
            return;
        }
        // A single failure keeps its own type, so the handler can still point at the broken element.
        if (failures.size() == 1) {
            throw failures.get(0);
        }
        // Every message already names its chain, so the count and the messages tell the whole story.
        String details = failures.stream()
                .map(Throwable::getMessage)
                .collect(Collectors.joining("; "));
        throw new DeploymentProcessingException("Unable to redeploy " + failures.size() + " of "
                + chainCount + " chains. Chains that still carry unsaved changes: " + details);
    }


    private List<ChainRolesDTO> getChainsFilteredByStatus(List<ChainRolesDTO> chainRolesResponse, List<ChainElementFilterRequestDTO> filters) {
        Predicate<ChainRolesDTO> predicate = filters.stream()
                .filter(chainFilter -> chainFilter.getColumn().equals(ChainElementFilterColumn.CHAIN_STATUS))
                .map(this::buildDeploymentStatusFilterPredicate)
                .reduce(chainRolesDTO -> true, Predicate::and);
        return chainRolesResponse.stream().filter(predicate).toList();
    }

    private Predicate<ChainRolesDTO> buildDeploymentStatusFilterPredicate(ChainElementFilterRequestDTO filter) {
        assert filter.getColumn().equals(ChainElementFilterColumn.CHAIN_STATUS);
        Collection<String> values = Arrays.stream(filter.getValue().split(","))
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        Predicate<String> predicate = switch (filter.getCondition()) {
            case IS, IN -> values::contains;
            case IS_NOT, NOT_IN -> status -> !values.contains(status);
            default -> status -> true;
        };
        return chainRolesDTO -> chainRolesDTO.getDeploymentStatus().stream()
                .map(status -> status.name().toLowerCase())
                .anyMatch(predicate);
    }

    private void setDeploymentStatuses(List<ChainRolesDTO> chainRolesResponse, Map<String, Collection<ChainRuntimeDeployment>> runtimeDeployments) {
        chainRolesResponse.forEach(chainRolesDTO -> chainRolesDTO.setDeploymentStatus(getDeploymentStatuses(chainRolesDTO.getChainId(), runtimeDeployments)));
    }

    private List<DeploymentStatus> getDeploymentStatuses(String chainId, Map<String, Collection<ChainRuntimeDeployment>> runtimeDeployments) {
        Collection<ChainRuntimeDeployment> chainDeployments = runtimeDeployments.get(chainId);
        if (chainDeployments != null) {
            return chainDeployments
                    .stream()
                    .map(EngineDeployment::getStatus)
                    .toList();
        }
        return Collections.singletonList(DeploymentStatus.DRAFT);
    }
}
