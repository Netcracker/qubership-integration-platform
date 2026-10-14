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
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.SnapshotLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.snapshot.SnapshotLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.snapshot.SnapshotRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.snapshot.SnapshotResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.SnapshotDTO;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring",
        uses = {
                MapperUtils.class,
                UserMapper.class,
                DependencyMapper.class,
                ElementMapper.class,
        }
)
public interface SnapshotMapper {
    @Mapping(source = "elements", target = "dependencies")
    @Mapping(source = "defaultSwimlane.id", target = "defaultSwimlaneId")
    @Mapping(source = "reuseSwimlane.id", target = "reuseSwimlaneId")
    SnapshotDTO asDTO(Snapshot snapshot);

    SnapshotResponse asResponse(Snapshot snapshot);

    List<SnapshotResponse> asResponse(List<Snapshot> snapshots);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "createdWhen", ignore = true)
    @Mapping(target = "modifiedWhen", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "modifiedBy", ignore = true)
    @Mapping(target = "xmlDefinition", ignore = true)
    @Mapping(target = "chain", ignore = true)
    @Mapping(target = "deployments", ignore = true)
    @Mapping(target = "elements", ignore = true)
    @Mapping(target = "defaultSwimlane", ignore = true)
    @Mapping(target = "reuseSwimlane", ignore = true)
    @Mapping(target = "maskedFields", ignore = true)
    Snapshot asRequest(SnapshotRequest snapshot);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "snapshot", ignore = true)
    SnapshotLabel asLabelRequest(SnapshotLabelDTO snapshotLabel);

    List<SnapshotLabel> asLabelRequests(List<SnapshotLabelDTO> snapshotLabel);

    SnapshotLabelDTO asLabelResponse(SnapshotLabel snapshotLabel);

    List<SnapshotLabelDTO> asLabelResponse(List<SnapshotLabel> snapshotLabel);
}
