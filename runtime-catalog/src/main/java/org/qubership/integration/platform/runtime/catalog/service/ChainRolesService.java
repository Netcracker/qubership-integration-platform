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
import org.qubership.integration.platform.catalog.exception.SnapshotCreationException;
import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.model.deployment.engine.ChainRuntimeDeployment;
import org.qubership.integration.platform.catalog.model.deployment.engine.DeploymentStatus;
import org.qubership.integration.platform.catalog.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.catalog.model.filter.ChainElementFilterColumn;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElementFilterRequestDTO;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElementSearchCriteria;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainRolesMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;


@Slf4j
@Service
public class ChainRolesService {
    private static final String ROLES = "roles";
    private final ElementService elementService;
    private final DeploymentService deploymentService;
    private final RuntimeDeploymentService runtimeDeploymentService;
    private final ChainRolesMapper chainRolesMapper;
    private final ChainService chainService;
    private final ElementRepository elementRepository;
    private final DeploymentMapper deploymentMapper;
    private final SnapshotService snapshotService;
    private final ActionsLogService actionLogger;

    private ChainRolesResponse chainRolesAndFilters;

    public ChainRolesService(ElementService elementService,
                             DeploymentService deploymentService,
                             RuntimeDeploymentService runtimeDeploymentService,
                             ChainRolesMapper chainRolesMapper, ChainService chainService,
                             ElementRepository elementRepository,
                             DeploymentMapper deploymentMapper,
                             SnapshotService snapshotService, ActionsLogService actionLogger) {
        this.elementService = elementService;
        this.deploymentService = deploymentService;
        this.runtimeDeploymentService = runtimeDeploymentService;
        this.chainRolesMapper = chainRolesMapper;
        this.chainService = chainService;
        this.elementRepository = elementRepository;
        this.deploymentMapper = deploymentMapper;
        this.snapshotService = snapshotService;
        this.actionLogger = actionLogger;
    }

    public ChainRolesResponse findAllChainByHttpTrigger(ChainElementSearchCriteria request, boolean isImplementedOnly) {
        int offset = request.getOffset();
        int limit = request.getLimit();
        List<ChainElementFilterRequestDTO> filters = request.getFilters();
        if (offset < 0 || limit < 1)
            return new ChainRolesResponse(0, Collections.emptyList());

        List<ChainElement> elementList = elementRepository.findElementsByFilter(offset, limit, List.of(CamelNames.HTTP_TRIGGER_COMPONENT), filters, isImplementedOnly);
        List<ChainRolesDTO> chainRolesResponse = chainRolesMapper.asChainRolesResponses(elementList);

        if (!chainRolesResponse.isEmpty()) {
            Map<String, Collection<ChainRuntimeDeployment>> runtimeDeployments = runtimeDeploymentService.getChainRuntimeDeployments();
            setDeploymentStatuses(chainRolesResponse, runtimeDeployments);
            chainRolesResponse = getChainsFilteredByStatus(chainRolesResponse, filters);
        }

        return chainFilters(new ChainRolesResponse(offset + chainRolesResponse.size(), chainRolesResponse));
    }


    public ChainRolesResponse chainFilters(ChainRolesResponse chainRolesResponse)
    {
        this.chainRolesAndFilters = chainRolesResponse;
        return chainRolesAndFilters;
    }

    public ChainRolesResponse updateRoles(List<UpdateRolesRequest> request) {
        List<UpdateRolesRequest> updateRolesRequestLst = new ArrayList<>();
        for (UpdateRolesRequest updateReq : request) {
            try {
                ChainElement element = elementService.findById(updateReq.getElementId());
                element.getProperties().put(ROLES, Lists.newArrayList(updateReq.getRoles()));
                element.getChain().setUnsavedChanges(true);
                UpdateRolesRequest updateRolesRequest = new UpdateRolesRequest(element.getChain().isUnsavedChanges(),
                        element.getChain().getId());
                elementService.save(element);
                if (updateReq.getIsRedeploy()) {
                    updateRolesRequestLst.add(updateRolesRequest);
                }
                actionLogger.logAction(ActionLog.builder()
                        .entityType(EntityType.ELEMENT)
                        .entityId(element.getId())
                        .entityName(element.getName())
                        .parentType(EntityType.CHAIN)
                        .parentId(element.getChain().getId())
                        .parentName(element.getChain().getName())
                        .operation(LogOperation.UPDATE)
                        .build());
            } catch (Exception exception) {
                log.error("Error when updating roles: {}", exception.getLocalizedMessage());
            }
        }
        return redeploy(updateRolesRequestLst);
    }

    public ChainRolesResponse redeploy(List<UpdateRolesRequest> request) {
        request.stream()
                .filter(UpdateRolesRequest::getUnsavedChanges)
                .map(UpdateRolesRequest::getChainId)
                .collect(Collectors.toSet()).forEach(chainId -> {
                    Chain chain = chainService.findById(chainId);
                    try {
                        List<Deployment> deployments = chain.getDeployments();
                        List<DeploymentRequest> deploymentRequestLst = new ArrayList<>();
                        Snapshot snapshot = snapshotService.build(chain.getId());
                        if (deployments.isEmpty()) {
                            DeploymentRequest deploymentRequest = chainRolesMapper.prepareDeploymentRequest(snapshot);
                            deploymentRequestLst.add(deploymentRequest);
                        } else {
                            chain.getDeployments().get(0).setSnapshot(snapshot);
                            deploymentRequestLst = chainRolesMapper.prepareDeploymentRequest(chain.getDeployments());
                        }
                        deploymentService.createAll(deploymentMapper.asEntities(deploymentRequestLst), chain.getId(), snapshot);
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
                });
            return chainRolesAndFilters;
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
