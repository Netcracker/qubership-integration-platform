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

import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.context.*;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        collectionMappingStrategy = CollectionMappingStrategy.SETTER_PREFERRED,
        uses = {
                MapperUtils.class
        }
)
public interface ContextSystemMapper {

    List<ContextSystemResponseDTO> toContextSystemResponsesDTOs(List<ContextSystem> contextSystems);


    ContextSystemResponseDTO toContextSystemResponseDTO(ContextSystem contextSystem);

    default ContextSystem toContextSystem(ContextSystemRequestDTO requestedContextSystem) {
        return ContextSystem.builder()
                .id(requestedContextSystem.getId())
                .name(requestedContextSystem.getName())
                .description(requestedContextSystem.getDescription())
                .build();
    }

    ContextSystem update(@MappingTarget ContextSystem contextSystem, ContextSystemUpdateRequestDTO request);
}
