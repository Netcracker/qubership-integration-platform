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

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationSourceDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModelLabel;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toModelSource;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceSource;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceUser;

@Slf4j
@Component
public class SystemModelDtoMapper {
    private final URI schemaUri;
    private final ApiOperationDtoMapper apiOperationDtoMapper;

    @Autowired
    public SystemModelDtoMapper(
            @Value("${qip.json.schemas.api:http://qubership.org/schemas/product/qip/api.schema.yaml}") URI schemaUri,
            ApiOperationDtoMapper apiOperationDtoMapper
    ) {
        this.schemaUri = schemaUri;
        this.apiOperationDtoMapper = apiOperationDtoMapper;
    }


    public SystemModel toInternalEntity(SystemModelDto systemModelDto) {
        SystemModel systemModel = SystemModel.builder()
                .id(systemModelDto.getId())
                .name(systemModelDto.getName())
                .description(systemModelDto.getContent().getDescription())
                .createdBy(SystemEntitySeam.toPersistenceUser(systemModelDto.getContent().getCreatedBy()))
                .createdWhen(systemModelDto.getContent().getCreatedWhen())
                .modifiedBy(SystemEntitySeam.toPersistenceUser(systemModelDto.getContent().getModifiedBy()))
                .modifiedWhen(systemModelDto.getContent().getModifiedWhen())
                .deprecated(systemModelDto.getContent().isDeprecated())
                .version(systemModelDto.getContent().getVersion())
                .specificationType(systemModelDto.getContent().getSpecificationType())
                .specificationVersion(systemModelDto.getContent().getSpecificationVersion())
                .source(SystemEntitySeam.toPersistenceSource(systemModelDto.getContent().getSource()))
                .operations(apiOperationDtoMapper.toEntities(systemModelDto.getContent().getOperations()))
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

    public SystemModel toInternalEntity(ImportSystemModel importSystemModel) {
        SystemModel systemModel = SystemModel.builder()
                .id(importSystemModel.getId())
                .name(importSystemModel.getName())
                .description(importSystemModel.getDescription())
                .createdBy(toPersistenceUser(importSystemModel.getCreatedBy()))
                .createdWhen(importSystemModel.getCreatedWhen())
                .modifiedBy(toPersistenceUser(importSystemModel.getModifiedBy()))
                .modifiedWhen(importSystemModel.getModifiedWhen())
                .deprecated(importSystemModel.isDeprecated())
                .version(importSystemModel.getVersion())
                .specificationType(importSystemModel.getSpecificationType())
                .specificationVersion(importSystemModel.getSpecificationVersion())
                .source(toPersistenceSource(importSystemModel.getSource()))
                // An operation read from a file carries the typed scalars the structural model has no room
                // for, so map through ApiOperationDtoMapper when they are there and fall back to the
                // structural seam for a model that was parsed rather than read.
                .operations(importSystemModel.getOperations().stream()
                        .map(operation -> operation.getExported() != null
                                ? apiOperationDtoMapper.toEntity(operation.getExported())
                                : SystemEntitySeam.toPersistenceOperation(operation))
                        .collect(Collectors.toCollection(LinkedList::new)))
                .build();
        systemModel.getOperations().forEach(operation -> operation.setSystemModel(systemModel));
        systemModel.getSpecificationSources().forEach(specificationSource -> specificationSource.setSystemModel(systemModel));
        systemModel.setLabels(importSystemModel
                .getLabels()
                .stream()
                .map(name -> new SystemModelLabel(name, systemModel))
                .collect(Collectors.toSet()));
        return systemModel;
    }

    public SystemModelDto toExternalEntity(SystemModel systemModel) {
        List<SpecificationSourceDto> specificationSources = systemModel.getSpecificationSources()
                .stream()
                .filter(source -> source.getSource() != null)
                .map(this::toSpecificationSourceDto)
                .toList();
        if (specificationSources.isEmpty()) {
            log.warn("Model {} has no specification source with content, so it exports an empty specifications list "
                    + "that the api schema rejects (minItems: 1). Re-import the model with its source files to repair it.",
                    systemModel.getId());
        }
        return SystemModelDto.builder()
                .id(systemModel.getId())
                .name(systemModel.getName())
                .schema(schemaUri)
                .content(SystemModelContentDto.builder()
                        .description(systemModel.getDescription())
                        .deprecated(systemModel.isDeprecated())
                        .version(systemModel.getVersion())
                        .specificationType(systemModel.getSpecificationType())
                        .specificationVersion(systemModel.getSpecificationVersion())
                        .source(toModelSource(systemModel.getSource()))
                        .operations(apiOperationDtoMapper.toDtos(systemModel.getOperations()))
                        .parentId(systemModel.getApiGroup().getId())
                        .labels(systemModel.getLabels().stream().map(SystemModelLabel::getName).toList())
                        .specificationSources(specificationSources)
                        .build())
                .build();
    }

    private SpecificationSourceDto toSpecificationSourceDto(SpecificationSource specificationSource) {
        return SpecificationSourceDto.builder()
                .id(specificationSource.getId())
                .name(specificationSource.getName())
                // No sourceHash: it is a storage detail of this instance, not part of the exported document.
                .mainSource(specificationSource.isMainSource())
                .fileName(ExportImportUtils.getFullSpecificationFileName(specificationSource))
                .build();
    }
}
