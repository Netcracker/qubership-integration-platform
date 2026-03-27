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

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        uses = {
                MapperUtils.class
        })
public interface ChainRolesMapper {

    List<ChainRolesDTO> asChainRolesResponses(List<ChainElement> chainElement);

    @Mapping(source = "id", target = "elementId")
    @Mapping(source = "name", target = "elementName")
    @Mapping(source = "properties", target = "properties")
    @Mapping(source = "modifiedWhen", target = "modifiedWhen")
    @Mapping(source = "chain.id", target = "chainId")
    @Mapping(source = "chain.name", target = "chainName")
    @Mapping(source = "chain.unsavedChanges", target = "unsavedChanges")
    ChainRolesDTO asChainRolesResponse(ChainElement chainElement);

    @Mapping(source = "deployment.domain", target = "domain")
    @Mapping(source = "deployment.snapshot.id", target = "snapshotId")
    @Mapping(source = "deployment.suspended", target = "suspended")
    DeploymentRequest prepareDeploymentRequest(Deployment deployment);

    List<DeploymentRequest> prepareDeploymentRequest(List<Deployment> deployment);

    @Mapping(target = "domain", constant = "default")
    @Mapping(source = "id", target = "snapshotId")
    DeploymentRequest prepareDeploymentRequest(Snapshot snapshot);
}
