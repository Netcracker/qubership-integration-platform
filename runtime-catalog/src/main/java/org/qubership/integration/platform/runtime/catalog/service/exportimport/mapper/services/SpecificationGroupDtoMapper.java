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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SpecificationGroupContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SpecificationGroupDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroupLabel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.stream.Collectors;

@Component
public class SpecificationGroupDtoMapper implements ExternalEntityMapper<SpecificationGroup, SpecificationGroupDto> {
    private final URI schemaUri;

    @Autowired
    public SpecificationGroupDtoMapper(
            @Value("${qip.json.schemas.specification-group:http://qubership.org/schemas/product/qip/specification-group}") URI schemaUri
    ) {
        this.schemaUri = schemaUri;
    }

    @Override
    public SpecificationGroup toInternalEntity(SpecificationGroupDto specificationGroupDto) {
        SpecificationGroup specificationGroup = SpecificationGroup.builder()
                .id(specificationGroupDto.getId())
                .name(specificationGroupDto.getName())
                .description(specificationGroupDto.getContent().getDescription())
                .createdBy(specificationGroupDto.getContent().getCreatedBy())
                .createdWhen(specificationGroupDto.getContent().getCreatedWhen())
                .modifiedBy(specificationGroupDto.getContent().getModifiedBy())
                .modifiedWhen(specificationGroupDto.getContent().getModifiedWhen())
                .url(specificationGroupDto.getContent().getUrl())
                .synchronization(specificationGroupDto.getContent().isSynchronization())
                .build();
        specificationGroup.setLabels(specificationGroupDto
                .getContent()
                .getLabels()
                .stream()
                .map(name -> new SpecificationGroupLabel(name, specificationGroup))
                .collect(Collectors.toSet()));
        return specificationGroup;
    }

    @Override
    public SpecificationGroupDto toExternalEntity(SpecificationGroup specificationGroup) {
        return SpecificationGroupDto.builder()
                .id(specificationGroup.getId())
                .name(specificationGroup.getName())
                .schema(schemaUri)
                .content(SpecificationGroupContentDto.builder()
                        .description(specificationGroup.getDescription())
                        .url(specificationGroup.getUrl())
                        .synchronization(specificationGroup.isSynchronization())
                        .parentId(specificationGroup.getSystem().getId())
                        .labels(specificationGroup.getLabels().stream().map(SpecificationGroupLabel::getName).toList())
                        .build())
                .build();
    }
}
