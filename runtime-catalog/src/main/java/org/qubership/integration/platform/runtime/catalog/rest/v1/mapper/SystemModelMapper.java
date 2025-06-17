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
import org.mapstruct.MappingTarget;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModelLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SystemModelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SystemModelLabelDTO;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring", uses = {
        MapperUtils.class,
        ChainBaseMapper.class,
        OperationBaseMapper.class
})
public interface SystemModelMapper {

    @Mapping(target = "id", source = "systemModel.id")
    @Mapping(target = "name", source = "systemModel.name")
    @Mapping(target = "specificationGroupId", source = "systemModel.specificationGroup.id")
    @Mapping(target = "deprecated", source = "systemModel.deprecated")
    @Mapping(target = "systemId", source = "systemModel.specificationGroup.system.id")
    SystemModelDTO toSystemModelDTO(SystemModel systemModel);

    List<SystemModelDTO> toSystemModelDTOs(List<SystemModel> systemModels);

    void merge(SystemModelDTO systemDto, @MappingTarget SystemModel systemModel);

    SystemModel asEntity(SystemModelDTO model);

    SystemModelLabel asLabelRequest(SystemModelLabelDTO snapshotLabel);

    List<SystemModelLabel> asLabelRequests(List<SystemModelLabelDTO> snapshotLabel);

    SystemModelLabelDTO asLabelResponse(SystemModelLabel snapshotLabel);

    List<SystemModelLabelDTO> asLabelResponse(List<SystemModelLabel> snapshotLabel);
}
