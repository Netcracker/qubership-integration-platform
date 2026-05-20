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

import org.mapstruct.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainsBySpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.FolderItemResponse;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.qubership.integration.platform.runtime.catalog.util.StringTrimmer;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        uses = {
                DependencyMapper.class,
                ElementMapper.class,
                DeploymentMapper.class,
                MapperUtils.class,
                UserMapper.class,
                StringTrimmer.class,
                ChainLabelsMapper.class
        })
public interface ChainMapper {

    @Mapping(source = "elements", target = "dependencies")
    @Mapping(source = "chain", target = "elements")
    @Mapping(source = "parentFolder.id", target = "parentId")
    @Mapping(source = "currentSnapshot.id", target = "currentSnapshot.id")
    @Mapping(source = "currentSnapshot.name", target = "currentSnapshot.name")
    @Mapping(source = "defaultSwimlane.id", target = "defaultSwimlaneId")
    @Mapping(source = "reuseSwimlane.id", target = "reuseSwimlaneId")
    @Mapping(source = "overriddenByChain.name", target = "overriddenByChainName")
    @Mapping(source = "overridesChain.name", target = "overridesChainName")
    ChainDTO asDTO(Chain chain);

    List<ChainDTO> asDTO(List<Chain> chains);

    @IterableMapping(elementTargetType = FolderItemResponse.class)
    List<FolderItemResponse> asFolderItemResponse(Collection<Chain> chains);

    @Mapping(source = "parentFolder.id", target = "parentId")
    @Mapping(source = "overriddenByChain.name", target = "overriddenByChainName")
    @Mapping(source = "overridesChain.name", target = "overridesChainName")
    FolderItemResponse asFolderItemResponse(Chain chain);

    @Mapping(source = "parentFolder.id", target = "parentId")
    @Mapping(source = "defaultSwimlane.id", target = "defaultSwimlaneId")
    @Mapping(source = "reuseSwimlane.id", target = "reuseSwimlaneId")
    ChainResponse asChainResponseLight(Chain chain);

    List<ChainResponse> asChainResponseLight(List<Chain> response);


    ChainsBySpecificationGroup asChainsBySpecificationGroup(String specificationGroupId, List<Chain> chains);

    default List<ChainsBySpecificationGroup> asChainsBySpecificationGroup(Map<String, List<Chain>> response) {
        return response.entrySet().stream().map(e -> asChainsBySpecificationGroup(e.getKey(), e.getValue())).toList();
    }

    Chain mapRequest(ChainRequest request);

    default Chain asEntity(ChainRequest request) {
        Chain chain = this.mapRequest(request);
        chain.getLabels().forEach(label -> label.setChain(chain));
        return chain;
    }

    @Mapping(target = "labels", ignore = true)
    void mergeWithoutLabels(@MappingTarget Chain chain, ChainRequest chainRequest);
}
