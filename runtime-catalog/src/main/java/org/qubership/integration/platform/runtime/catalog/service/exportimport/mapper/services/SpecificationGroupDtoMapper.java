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

import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroupLabel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceUser;

@Component
public class SpecificationGroupDtoMapper {
    private final URI schemaUri;

    @Autowired
    public SpecificationGroupDtoMapper(
            @Value("${qip.json.schemas.specification-group:http://qubership.org/schemas/product/qip/specification-group}") URI schemaUri
    ) {
        this.schemaUri = schemaUri;
    }

    public SpecificationGroup toInternalEntity(ImportSpecificationGroup importSpecificationGroup) {
        SpecificationGroup specificationGroup = SpecificationGroup.builder()
                .id(importSpecificationGroup.getId())
                .name(importSpecificationGroup.getName())
                .description(importSpecificationGroup.getDescription())
                .createdBy(toPersistenceUser(importSpecificationGroup.getCreatedBy()))
                .createdWhen(importSpecificationGroup.getCreatedWhen())
                .modifiedBy(toPersistenceUser(importSpecificationGroup.getModifiedBy()))
                .modifiedWhen(importSpecificationGroup.getModifiedWhen())
                .url(importSpecificationGroup.getUrl())
                .synchronization(importSpecificationGroup.isSynchronization())
                .build();
        specificationGroup.setLabels(importSpecificationGroup
                .getLabels()
                .stream()
                .map(name -> new SpecificationGroupLabel(name, specificationGroup))
                .collect(Collectors.toSet()));
        return specificationGroup;
    }

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
