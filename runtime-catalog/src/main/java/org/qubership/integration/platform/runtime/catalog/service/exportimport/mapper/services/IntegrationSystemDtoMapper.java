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

import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystemLabel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toModelProtocol;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toModelType;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceProtocol;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceType;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceUser;

@Component
public class IntegrationSystemDtoMapper {
    private final URI schemaUri;
    private final List<ServiceImportFileMigration> serviceImportFileMigrations;

    @Autowired
    public IntegrationSystemDtoMapper(
            @Value("${qip.json.schemas.service:http://qubership.org/schemas/product/qip/service}") URI schemaUri,
            List<ServiceImportFileMigration> serviceImportFileMigrations
    ) {
        this.schemaUri = schemaUri;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
    }

    public IntegrationSystem toInternalEntity(ImportSystem importSystem) {
        IntegrationSystem system = IntegrationSystem.builder()
                .id(importSystem.getId())
                .name(importSystem.getName())
                .description(importSystem.getDescription())
                .createdBy(toPersistenceUser(importSystem.getCreatedBy()))
                .createdWhen(importSystem.getCreatedWhen())
                .modifiedBy(toPersistenceUser(importSystem.getModifiedBy()))
                .modifiedWhen(importSystem.getModifiedWhen())
                .activeEnvironmentId(importSystem.getActiveEnvironmentId())
                .integrationSystemType(toPersistenceType(importSystem.getIntegrationSystemType()))
                .internalServiceName(importSystem.getInternalServiceName())
                .protocol(toPersistenceProtocol(importSystem.getProtocol()))
                .environments(importSystem.getEnvironments().stream()
                        .map(SystemEntitySeam::toPersistenceEnvironment)
                        .collect(Collectors.toCollection(java.util.LinkedList::new)))
                .build();
        system.getEnvironments().forEach(environment -> environment.setSystem(system));
        system.setLabels(importSystem
                .getLabels()
                .stream()
                .map(name -> new IntegrationSystemLabel(name, system))
                .collect(Collectors.toSet()));
        return system;
    }

    public IntegrationSystemDto toExternalEntity(IntegrationSystem integrationSystem) {
        return IntegrationSystemDto.builder()
                .id(integrationSystem.getId())
                .name(integrationSystem.getName())
                .schema(schemaUri)
                .content(IntegrationSystemContentDto.builder()
                        .description(integrationSystem.getDescription())
                        .activeEnvironmentId(integrationSystem.getActiveEnvironmentId())
                        .integrationSystemType(toModelType(integrationSystem.getIntegrationSystemType()))
                        .internalServiceName(integrationSystem.getInternalServiceName())
                        .protocol(toModelProtocol(integrationSystem.getProtocol()))
                        .environments(integrationSystem.getEnvironments().stream()
                                .map(SystemEntitySeam::toModelEnvironment)
                                .toList())
                        .labels(integrationSystem.getLabels().stream().map(IntegrationSystemLabel::getName).toList())
                        .migrations(MigrationUtil.formatVersions(serviceImportFileMigrations))
                        .build())
                .build();
    }
}
