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

package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.ChainRuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.RuntimeDeploymentState;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.EngineDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.RuntimeDeploymentUpdate;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Mapper(
        componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        uses = { UserMapper.class, MapperUtils.class}
)
public abstract class DeploymentMapper {

    @Autowired
    private RuntimeDeploymentService runtimeDeploymentService;
    @Autowired
    private SnapshotService snapshotService;
    @Autowired
    private ChainFinderService chainFinderService;

    @Mapping(source = "snapshot.id", target = "snapshotId")
    @Mapping(source = "chain.id", target = "chainId")
    public abstract DeploymentResponse asResponse(Deployment deployment);

    @Mapping(source = "deployment.id", target = "id")
    @Mapping(source = "deployment.chain.id", target = "chainId")
    @Mapping(source = "deployment.snapshot.id", target = "snapshotId")
    @Mapping(source = "deployment.domain", target = "domain")
    @Mapping(source = "deployment.suspended", target = "suspended")
    @Mapping(source = "state", target = "runtime")
    @Mapping(source = "deployment.name", target = "name")
    public abstract DeploymentResponse asResponse(Deployment deployment, RuntimeDeployment state);

    public List<DeploymentResponse> asResponses(List<Deployment> deploymentEntityEngineList) {
        if (deploymentEntityEngineList == null) {
            return null;
        }

        return deploymentEntityEngineList.stream()
                .map(deployment -> asResponse(deployment, runtimeDeploymentService.getRuntimeDeployment(deployment.getId())))
                .collect(Collectors.toList());
    }

    @Mapping(source = "status", target = "status")
    @Mapping(source = "errorMessage", target = "error")
    public abstract RuntimeDeploymentState toDTO(EngineDeployment state);

    @Mapping(source = "deployment.deploymentInfo.deploymentId", target = "id")
    @Mapping(source = "deployment.deploymentInfo.chainId", target = "chainId")
    @Mapping(source = "deployment.deploymentInfo.chainName", target = "chainName")
    @Mapping(source = "deployment.deploymentInfo.chainStatusCode", target = "chainStatusCode")
    @Mapping(source = "deployment.deploymentInfo.snapshotId", target = "snapshotId")
    @Mapping(source = "deployment.errorMessage", target = "state.error")
    @Mapping(source = "deployment.status", target = "state.status")

    @Mapping(source = "engineInfo.host", target = "engineHost")
    @Mapping(source = "engineInfo.domain", target = "domain")
    @Mapping(source = "engineInfo.engineDeploymentName", target = "serviceName")
    @Mapping(source = "loggingInfo.createdWhen", target = "createdWhen")
    public abstract RuntimeDeploymentUpdate toRuntimeUpdate(
            EngineDeployment deployment,
            EngineInfo engineInfo,
            DeploymentService.LoggingInfo loggingInfo);


    public List<EngineDeploymentResponse> asEngineDeployments(Collection<EngineDeployment> deployments) {
        return deployments.stream().map(this::asEngineDeployment).collect(Collectors.toList());
    }

    public EngineDeploymentResponse asEngineDeployment(EngineDeployment deployment) {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        String chainName = chainFinderService.tryFindById(deploymentInfo.getChainId())
                .map(AbstractEntity::getName).orElse(null);
        String snapshotName = snapshotService.tryFindById(deploymentInfo.getSnapshotId())
                .map(AbstractEntity::getName).orElse(null);

        return EngineDeploymentResponse.builder()
                .id(deploymentInfo.getDeploymentId())
                .chainId(deploymentInfo.getChainId())
                .chainName(chainName)
                .snapshotName(snapshotName)
                .state(toDTO(deployment))
                .build();
    }

    @Mapping(source = "snapshotId", target = "snapshot.id")
    public abstract Deployment asEntity(DeploymentRequest request);

    public List<Deployment> asEntities(List<DeploymentRequest> request) {
        return request.stream().map(this::asEntity).collect(Collectors.toList());
    }

    public abstract void merge(@MappingTarget Deployment entity, DeploymentRequest request);

    public abstract ChainRuntimeDeployment toChainRuntimeDeployment(EngineDeployment deployment, String host);

}
