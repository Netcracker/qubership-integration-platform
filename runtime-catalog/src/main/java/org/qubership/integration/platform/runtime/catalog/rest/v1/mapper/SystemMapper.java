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
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystemLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(
        componentModel = "spring",
        uses = {
                MapperUtils.class
        }
)
public interface SystemMapper {

    @Mapping(source = "system.integrationSystemType", target = "type")
    @Mapping(target = "protocol", source = "system.protocol.value")
    @Mapping(target = "extendedProtocol", source = "system.protocol")
    @Mapping(target = "specification", source = "system.protocol.type")
    @Mapping(target = "activeEnvironmentId", expression = "java(getActiveEnvironmentId(system))")
    SystemDTO toDTO(IntegrationSystem system);

    default String protocolToString(OperationProtocol protocol) {
        if (protocol == null) {
            return null;
        }
        return protocol == OperationProtocol.SOAP ? "soap" : protocol.value;
    }

    @Mapping(source = "dto.type", target = "integrationSystemType")
    IntegrationSystem toSystem(SystemRequestDTO dto);

    List<SystemDTO> toResponseDTOs(List<IntegrationSystem> systems);

    @Mapping(target = "labels", ignore = true)
    void mergeWithoutLabels(SystemRequestDTO systemDto, @MappingTarget IntegrationSystem system);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "labels", ignore = true)
    void patchMergeWithoutLabels(SystemRequestDTO systemDto, @MappingTarget IntegrationSystem system);

    @SuppressWarnings("unused")
    default String getActiveEnvironmentId(IntegrationSystem system) {
        IntegrationSystemType type = system.getIntegrationSystemType();
        if (type == IntegrationSystemType.INTERNAL || type == IntegrationSystemType.IMPLEMENTED) {
            return system.getEnvironments().isEmpty() ? null : system.getEnvironments().get(0).getId();
        }
        return system.getActiveEnvironmentId();
    }

    IntegrationSystemLabel asLabelRequest(SystemLabelDTO snapshotLabel);

    List<IntegrationSystemLabel> asLabelRequests(List<SystemLabelDTO> snapshotLabel);

    SystemLabelDTO asLabelResponse(IntegrationSystemLabel snapshotLabel);

    List<SystemLabelDTO> asLabelResponse(List<IntegrationSystemLabel> snapshotLabel);
}
