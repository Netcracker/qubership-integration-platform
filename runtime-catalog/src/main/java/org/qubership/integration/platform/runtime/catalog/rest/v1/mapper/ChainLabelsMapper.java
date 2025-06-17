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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainLabelDTO;

import java.util.List;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ChainLabelsMapper {
    ChainLabelDTO asDTO(ChainLabel label);

    @Mapping(source = "chain", target = "chain")
    @Mapping(source = "dto.name", target = "name")
    @Mapping(source = "dto.technical", target = "technical")
    ChainLabel asEntity(ChainLabelDTO dto, Chain chain);

    List<ChainLabelDTO> asDTOs(List<ChainLabel> label);

    List<ChainLabel> asEntities(List<ChainLabelDTO> label);
}
