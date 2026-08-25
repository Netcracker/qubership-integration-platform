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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroupLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.ApiGroupRequestDTO;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring", uses = {
        MapperUtils.class,
        SystemModelBaseMapper.class,
        ChainBaseMapper.class
})
public interface ApiGroupMapper {
    @Mapping(target = "systemId", source = "apiGroup.system.id")
    @Mapping(target = "specifications", source = "apiGroup.systemModels")
    ApiGroupDTO toApiGroupDTO(ApiGroup apiGroup);

    List<ApiGroupDTO> toApiGroupDTOs(List<ApiGroup> apiGroups);

    void mergeWithoutLabels(ApiGroupDTO apiGroupDTO, @MappingTarget ApiGroup apiGroup);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "labels", ignore = true)
    void mergeWithoutLabels(ApiGroupRequestDTO apiGroupDTO, @MappingTarget ApiGroup apiGroup);

    ApiGroupLabel asLabelRequest(ApiGroupLabelDTO snapshotLabel);

    List<ApiGroupLabel> asLabelRequests(List<ApiGroupLabelDTO> snapshotLabel);

    ApiGroupLabelDTO asLabelResponse(ApiGroupLabel snapshotLabel);

    List<ApiGroupLabelDTO> asLabelResponse(List<ApiGroupLabel> snapshotLabel);
}
