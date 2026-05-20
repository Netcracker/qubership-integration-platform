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

import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ContextServiceContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ContextServiceDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
public class ContextServiceDtoMapper implements ExternalEntityMapper<ContextSystem, ContextServiceDto> {
    private final URI schemaUri;
    private final List<ServiceImportFileMigration> serviceImportFileMigrations;

    @Autowired
    public ContextServiceDtoMapper(
            @Value("${qip.json.schemas.context-service:http://qubership.org/schemas/product/qip/context-service}") URI schemaUri,
            List<ServiceImportFileMigration> serviceImportFileMigrations
    ) {
        this.schemaUri = schemaUri;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
    }

    @Override
    public ContextSystem toInternalEntity(ContextServiceDto contextServiceDto) {
        ContextSystem systems = ContextSystem.builder()
                .id(contextServiceDto.getId())
                .name(contextServiceDto.getName())
                .modifiedWhen(contextServiceDto.getContent().getModifiedWhen())
                .description(contextServiceDto.getContent().getDescription())
                .build();
        return systems;
    }


    @Override
    public ContextServiceDto toExternalEntity(ContextSystem contextSystem) {
        return ContextServiceDto.builder()
                .id(contextSystem.getId())
                .name(contextSystem.getName())
                .schema(schemaUri)
                .content(ContextServiceContentDto.builder()
                        .description(contextSystem.getDescription())
                        .modifiedWhen(contextSystem.getModifiedWhen())
                        .migrations(serviceImportFileMigrations
                                .stream()
                                .map(ImportFileMigration::getVersion)
                                .sorted()
                                .toList()
                                .toString())
                        .build())
                .build();
    }
}
