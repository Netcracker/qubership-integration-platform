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

import org.qubership.integration.platform.io.model.exportimport.system.ApiGroupContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.ApiGroupDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroupLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.stream.Collectors;

@Component
public class ApiGroupDtoMapper implements ExternalEntityMapper<ApiGroup, ApiGroupDto> {
    private final URI schemaUri;

    @Autowired
    public ApiGroupDtoMapper(
            @Value("${qip.json.schemas.api-group:http://qubership.org/schemas/product/qip/api-group.schema.yaml}") URI schemaUri
    ) {
        this.schemaUri = schemaUri;
    }

    @Override
    public ApiGroup toInternalEntity(ApiGroupDto apiGroupDto) {
        ApiGroup apiGroup = ApiGroup.builder()
                .id(apiGroupDto.getId())
                .name(apiGroupDto.getName())
                .description(apiGroupDto.getContent().getDescription())
                .createdBy(SystemEntitySeam.toPersistenceUser(apiGroupDto.getContent().getCreatedBy()))
                .createdWhen(apiGroupDto.getContent().getCreatedWhen())
                .modifiedBy(SystemEntitySeam.toPersistenceUser(apiGroupDto.getContent().getModifiedBy()))
                .modifiedWhen(apiGroupDto.getContent().getModifiedWhen())
                .url(apiGroupDto.getContent().getUrl())
                .synchronization(apiGroupDto.getContent().isSynchronization())
                .build();
        apiGroup.setLabels(apiGroupDto
                .getContent()
                .getLabels()
                .stream()
                .map(name -> new ApiGroupLabel(name, apiGroup))
                .collect(Collectors.toSet()));
        return apiGroup;
    }

    @Override
    public ApiGroupDto toExternalEntity(ApiGroup apiGroup) {
        return ApiGroupDto.builder()
                .id(apiGroup.getId())
                .name(apiGroup.getName())
                .schema(schemaUri)
                .content(ApiGroupContentDto.builder()
                        .description(apiGroup.getDescription())
                        .url(apiGroup.getUrl())
                        .synchronization(apiGroup.isSynchronization())
                        .parentId(apiGroup.getSystem().getId())
                        .labels(apiGroup.getLabels().stream().map(ApiGroupLabel::getName).toList())
                        .apis(apiGroup.getSystemModels().stream().map(SystemModel::getId).toList())
                        .build())
                .build();
    }
}
