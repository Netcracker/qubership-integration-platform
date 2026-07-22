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
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toModelSource;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceSource;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceUser;

@Component
public class SystemModelDtoMapper {
    private final URI schemaUri;

    @Autowired
    public SystemModelDtoMapper(
            @Value("${qip.json.schemas.specification:http://qubership.org/schemas/product/qip/specification}") URI schemaUri
    ) {
        this.schemaUri = schemaUri;
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
                .source(toPersistenceSource(importSystemModel.getSource()))
                .operations(importSystemModel.getOperations().stream()
                        .map(SystemEntitySeam::toPersistenceOperation)
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
        return SystemModelDto.builder()
                .id(systemModel.getId())
                .name(systemModel.getName())
                .schema(schemaUri)
                .content(SystemModelContentDto.builder()
                        .description(systemModel.getDescription())
                        .deprecated(systemModel.isDeprecated())
                        .version(systemModel.getVersion())
                        .source(toModelSource(systemModel.getSource()))
                        .operations(systemModel.getOperations().stream()
                                .map(SystemEntitySeam::toModelOperation)
                                .toList())
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
