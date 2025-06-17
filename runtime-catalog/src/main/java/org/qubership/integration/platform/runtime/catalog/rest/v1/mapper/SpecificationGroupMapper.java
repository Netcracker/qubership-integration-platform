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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroupLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SpecificationGroupDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SpecificationGroupLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SpecificationGroupRequestDTO;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring", uses = {
        MapperUtils.class,
        SystemModelBaseMapper.class,
        ChainBaseMapper.class
})
public interface SpecificationGroupMapper {
    @Mapping(target = "systemId", source = "specificationGroup.system.id")
    @Mapping(target = "specifications", source = "specificationGroup.systemModels")
    SpecificationGroupDTO toSpecificationGroupDTO(SpecificationGroup specificationGroup);

    List<SpecificationGroupDTO> toSpecificationGroupDTOs(List<SpecificationGroup> specificationGroups);

    void mergeWithoutLabels(SpecificationGroupDTO specificationGroupDTO, @MappingTarget SpecificationGroup specificationGroup);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "labels", ignore = true)
    void mergeWithoutLabels(SpecificationGroupRequestDTO specificationGroupDTO, @MappingTarget SpecificationGroup specificationGroup);

    SpecificationGroupLabel asLabelRequest(SpecificationGroupLabelDTO snapshotLabel);

    List<SpecificationGroupLabel> asLabelRequests(List<SpecificationGroupLabelDTO> snapshotLabel);

    SpecificationGroupLabelDTO asLabelResponse(SpecificationGroupLabel snapshotLabel);

    List<SpecificationGroupLabelDTO> asLabelResponse(List<SpecificationGroupLabel> snapshotLabel);
}
