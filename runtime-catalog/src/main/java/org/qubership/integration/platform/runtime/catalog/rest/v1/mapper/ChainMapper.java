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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainsBySpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.FolderItemResponse;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.qubership.integration.platform.runtime.catalog.util.StringTrimmer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    @Mapping(target = "navigationPath", ignore = true)
    @Mapping(target = "containsDeprecatedContainers", ignore = true)
    @Mapping(target = "containsDeprecatedElements", ignore = true)
    @Mapping(target = "containsUnsupportedElements", ignore = true)
    ChainDTO asDTO(Chain chain);

    List<ChainDTO> asDTO(List<Chain> chains);

    @IterableMapping(elementTargetType = FolderItemResponse.class)
    List<FolderItemResponse> asFolderItemResponse(Collection<Chain> chains);

    @Mapping(source = "parentFolder.id", target = "parentId")
    @Mapping(source = "overriddenByChain.name", target = "overriddenByChainName")
    @Mapping(source = "overridesChain.name", target = "overridesChainName")
    @Mapping(target = "itemType", ignore = true)
    @Mapping(target = "chainRuntimeProperties", ignore = true)
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

    // Declared only to host RETURN_DEFAULT: MapStruct otherwise passes null to the Chain builder.
    @IterableMapping(nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
    Set<ChainLabel> asLabelEntities(List<ChainLabelDTO> labels);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdWhen", ignore = true)
    @Mapping(target = "modifiedWhen", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "modifiedBy", ignore = true)
    @Mapping(target = "parentFolder", ignore = true)
    @Mapping(target = "elements", ignore = true)
    @Mapping(target = "snapshots", ignore = true)
    @Mapping(target = "deployments", ignore = true)
    @Mapping(target = "validationAlerts", ignore = true)
    @Mapping(target = "defaultSwimlane", ignore = true)
    @Mapping(target = "reuseSwimlane", ignore = true)
    @Mapping(target = "maskedFields", ignore = true)
    @Mapping(target = "currentSnapshot", ignore = true)
    @Mapping(target = "overriddenByChainId", ignore = true)
    @Mapping(target = "overriddenByChain", ignore = true)
    @Mapping(target = "overridesChainId", ignore = true)
    @Mapping(target = "overridesChain", ignore = true)
    @Mapping(target = "unsavedChanges", ignore = true)
    @Mapping(target = "lastImportHash", ignore = true)
    Chain mapRequest(ChainRequest request);

    default Chain asEntity(ChainRequest request) {
        Chain chain = this.mapRequest(request);
        chain.getLabels().forEach(label -> label.setChain(chain));
        return chain;
    }

    @Mapping(target = "labels", ignore = true)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdWhen", ignore = true)
    @Mapping(target = "modifiedWhen", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "modifiedBy", ignore = true)
    @Mapping(target = "parentFolder", ignore = true)
    @Mapping(target = "elements", ignore = true)
    @Mapping(target = "snapshots", ignore = true)
    @Mapping(target = "deployments", ignore = true)
    @Mapping(target = "validationAlerts", ignore = true)
    @Mapping(target = "defaultSwimlane", ignore = true)
    @Mapping(target = "reuseSwimlane", ignore = true)
    @Mapping(target = "maskedFields", ignore = true)
    @Mapping(target = "currentSnapshot", ignore = true)
    @Mapping(target = "overriddenByChainId", ignore = true)
    @Mapping(target = "overriddenByChain", ignore = true)
    @Mapping(target = "overridesChainId", ignore = true)
    @Mapping(target = "overridesChain", ignore = true)
    @Mapping(target = "unsavedChanges", ignore = true)
    @Mapping(target = "lastImportHash", ignore = true)
    @Mapping(target = "ancestors", ignore = true)
    @Mapping(target = "rootElements", ignore = true)
    @Mapping(target = "dependencies", ignore = true)
    void mergeWithoutLabels(@MappingTarget Chain chain, ChainRequest chainRequest);
}
