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

import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SpecificationSourceDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SystemModelContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModelLabel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.stream.Collectors;

@Component
public class SystemModelDtoMapper implements ExternalEntityMapper<SystemModel, SystemModelDto> {
    private final URI schemaUri;

    @Autowired
    public SystemModelDtoMapper(
            @Value("${qip.json.schemas.specification:http://qubership.org/schemas/product/qip/specification}") URI schemaUri
    ) {
        this.schemaUri = schemaUri;
    }

    @Override
    public SystemModel toInternalEntity(SystemModelDto systemModelDto) {
        SystemModel systemModel = SystemModel.builder()
                .id(systemModelDto.getId())
                .name(systemModelDto.getName())
                .description(systemModelDto.getContent().getDescription())
                .createdBy(systemModelDto.getContent().getCreatedBy())
                .createdWhen(systemModelDto.getContent().getCreatedWhen())
                .modifiedBy(systemModelDto.getContent().getModifiedBy())
                .modifiedWhen(systemModelDto.getContent().getModifiedWhen())
                .deprecated(systemModelDto.getContent().isDeprecated())
                .version(systemModelDto.getContent().getVersion())
                .source(systemModelDto.getContent().getSource())
                .operations(systemModelDto.getContent().getOperations())
                .build();
        systemModel.getOperations().forEach(operation -> operation.setSystemModel(systemModel));
        systemModel.getSpecificationSources().forEach(specificationSource -> specificationSource.setSystemModel(systemModel));
        systemModel.setLabels(systemModelDto
                .getContent()
                .getLabels()
                .stream()
                .map(name -> new SystemModelLabel(name, systemModel))
                .collect(Collectors.toSet()));
        return systemModel;
    }

    @Override
    public SystemModelDto toExternalEntity(SystemModel systemModel) {
        return SystemModelDto.builder()
                .id(systemModel.getId())
                .name(systemModel.getName())
                .schema(schemaUri)
                .content(SystemModelContentDto.builder()
                        .description(systemModel.getDescription())
                        .deprecated(systemModel.isDeprecated())
                        .version(systemModel.getVersion())
                        .source(systemModel.getSource())
                        .operations(systemModel.getOperations())
                        .parentId(systemModel.getSpecificationGroup().getId())
                        .labels(systemModel.getLabels().stream().map(SystemModelLabel::getName).toList())
                        .specificationSources(systemModel.getSpecificationSources()
                                .stream()
                                .filter(model -> model.getSource() != null)
                                .map(this::toSpecificationSourceDto)
                                .toList())
                        .build())
                .build();
    }

    private SpecificationSourceDto toSpecificationSourceDto(SpecificationSource specificationSource) {
        return SpecificationSourceDto.builder()
                .id(specificationSource.getId())
                .name(specificationSource.getName())
                .sourceHash(specificationSource.getSourceHash())
                .mainSource(specificationSource.isMainSource())
                .fileName(ExportImportUtils.getFullSpecificationFileName(specificationSource))
                .build();
    }
}
