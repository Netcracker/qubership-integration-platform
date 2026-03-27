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

import org.apache.commons.lang3.StringUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveredServiceDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveredServiceGroupDTO;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring", uses = {MapperUtils.class})
public abstract class DiscoveryServiceMapper {

    private SystemModelService systemModelService;

    @Autowired
    public void setService(SystemModelService systemModelService) {
        this.systemModelService = systemModelService;
    }

    public abstract DiscoveredServiceDTO toDiscoveredServiceDTO(IntegrationSystem system);

    @AfterMapping
    protected void after(IntegrationSystem system, @MappingTarget DiscoveredServiceDTO discoveredServiceDTO) {
        if (system.getSpecificationGroups() == null) {
            return;
        }

        List<DiscoveredServiceGroupDTO> groupList = new ArrayList<>();
        for (SpecificationGroup group : system.getSpecificationGroups()) {
            if (StringUtils.isBlank(group.getUrl())) { // Not discovered group
                continue;
            }
            DiscoveredServiceGroupDTO discoveredServiceGroupDTO = toDiscoveredServiceGroupDTO(group);
            if (StringUtils.isBlank(discoveredServiceGroupDTO.getSpecificationId())) {
                continue;
            }
            groupList.add(discoveredServiceGroupDTO);
        }

        if (!CollectionUtils.isEmpty(groupList)) {
            discoveredServiceDTO.setServiceGroups(groupList);
        }
    }

    public abstract DiscoveredServiceGroupDTO toDiscoveredServiceGroupDTO(SpecificationGroup group);

    @AfterMapping
    protected void afterGroup(SpecificationGroup group, @MappingTarget DiscoveredServiceGroupDTO discoveredServiceGroupDTO) {
        SystemModel model = systemModelService.getLastDiscoveredSystemModelInGroup(group.getId());
        if (model != null) {
            discoveredServiceGroupDTO.setSpecificationId(model.getId());
            discoveredServiceGroupDTO.setSpecificationName(model.getName());
        }
    }

    public abstract List<DiscoveredServiceDTO> toDiscoveredServiceDTOs(List<IntegrationSystem> systems);

    public abstract List<DiscoveredServiceGroupDTO> toDiscoveredServiceGroupDTOs(List<SpecificationGroup> groups);

}
