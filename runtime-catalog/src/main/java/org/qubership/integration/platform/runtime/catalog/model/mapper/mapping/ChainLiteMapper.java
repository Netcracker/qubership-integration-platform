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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.SubclassMapping;
import org.qubership.integration.platform.runtime.catalog.model.dto.chain.ChainLiteDTO;
import org.qubership.integration.platform.runtime.catalog.model.dto.dependency.DependencyResponse;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;
import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.util.MapperUtils.extractOriginalId;


@Mapper(componentModel = "spring", uses = MapperUtils.class)
public interface ChainLiteMapper {

    @SubclassMapping(target = ChainLiteDTO.class, source = Chain.class)
    @SubclassMapping(target = ChainLiteDTO.class, source = Snapshot.class)
    ChainLiteDTO asDto(AbstractEntity entity);

    @Mapping(target = "currentSnapshotId", source = "id")
    @Mapping(target = "currentSnapshotName", source = "name")
    @Mapping(target = "id", source = "chain.id")
    @Mapping(target = "name", source = "chain.name")
    ChainLiteDTO asDto(Snapshot snapshot);

    default List<DependencyResponse> asDependencyResponses(Set<Dependency> dependencies) {
        return dependencies.stream()
                .map(this::asDependencyResponse)
                .toList();
    }

    default DependencyResponse asDependencyResponse(Dependency dependency) {
        return DependencyResponse.builder()
                .id(dependency.getId())
                .from(extractOriginalId(dependency.getElementFrom()))
                .to(extractOriginalId(dependency.getElementTo()))
                .build();
    }
}
