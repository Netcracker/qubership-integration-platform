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

package org.qubership.integration.platform.designtime.catalog.rest.v1.mapping;

import org.qubership.integration.platform.catalog.mapping.UserMapper;
import org.qubership.integration.platform.catalog.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.catalog.model.dto.deployment.RuntimeDeploymentState;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.catalog.util.MapperUtils;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Slf4j
@Mapper(
        componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        uses = { UserMapper.class, MapperUtils.class}
)
public abstract class DeploymentMapper {

    @Mapping(source = "snapshot.id", target = "snapshotId")
    @Mapping(source = "chain.id", target = "chainId")
    public abstract DeploymentResponse asResponse(Deployment deployment);

    public abstract List<DeploymentResponse> asResponses(List<Deployment> deploymentEntityEngineList);

    @Mapping(source = "status", target = "status")
    @Mapping(source = "errorMessage", target = "error")
    public abstract RuntimeDeploymentState toDTO(EngineDeployment state);




}
