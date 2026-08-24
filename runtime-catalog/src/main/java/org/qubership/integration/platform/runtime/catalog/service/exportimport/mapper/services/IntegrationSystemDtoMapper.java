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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceExportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystemLabel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toModelProtocol;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceProtocol;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceType;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam.toPersistenceUser;

@Component
public class IntegrationSystemDtoMapper implements ExternalEntityMapper<IntegrationSystem, IntegrationSystemDto> {
    private final ServiceTypeFiles serviceTypeFiles;
    private final List<ServiceImportFileMigration> serviceImportFileMigrations;

    @Autowired
    public IntegrationSystemDtoMapper(
            ServiceTypeFiles serviceTypeFiles,
            List<ServiceImportFileMigration> serviceImportFileMigrations
    ) {
        this.serviceTypeFiles = serviceTypeFiles;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
    }


    @Override
    public IntegrationSystem toInternalEntity(IntegrationSystemDto integrationSystemDto) {
        IntegrationSystem system = IntegrationSystem.builder()
                .id(integrationSystemDto.getId())
                .name(integrationSystemDto.getName())
                .description(integrationSystemDto.getContent().getDescription())
                .createdBy(SystemEntitySeam.toPersistenceUser(integrationSystemDto.getContent().getCreatedBy()))
                .createdWhen(integrationSystemDto.getContent().getCreatedWhen())
                .modifiedBy(SystemEntitySeam.toPersistenceUser(integrationSystemDto.getContent().getModifiedBy()))
                .modifiedWhen(integrationSystemDto.getContent().getModifiedWhen())
                .activeEnvironmentId(integrationSystemDto.getContent().getActiveEnvironmentId())
                .integrationSystemType(SystemEntitySeam.toPersistenceType(integrationSystemDto.getContent().getIntegrationSystemType()))
                .internalServiceName(integrationSystemDto.getContent().getInternalServiceName())
                .protocol(SystemEntitySeam.toPersistenceProtocol(integrationSystemDto.getContent().getProtocol()))
                .environments(integrationSystemDto.getContent().getEnvironments() == null
                        ? null
                        : integrationSystemDto.getContent().getEnvironments().stream()
                                .map(SystemEntitySeam::toPersistenceEnvironment)
                                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)))
                .build();
        system.getEnvironments().forEach(environment -> environment.setSystem(system));
        system.setLabels(integrationSystemDto
                .getContent()
                .getLabels()
                .stream()
                .map(name -> new IntegrationSystemLabel(name, system))
                .collect(Collectors.toSet()));
        return system;
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

    /**
     * Builds the export document. The {@code $schema} is the only place the type states itself: the file name states a
     * kind, never a type, and {@code IntegrationSystemContentDto.integrationSystemType} is write-only and no longer
     * carries it. A service without a type therefore cannot be exported at all.
     */
    @Override
    public IntegrationSystemDto toExternalEntity(IntegrationSystem integrationSystem) {
        return IntegrationSystemDto.builder()
                .id(integrationSystem.getId())
                .name(integrationSystem.getName())
                .schema(URI.create(serviceTypeFiles.schemaUri(requireType(integrationSystem))))
                .content(IntegrationSystemContentDto.builder()
                        .description(integrationSystem.getDescription())
                        .activeEnvironmentId(integrationSystem.getActiveEnvironmentId())
                        // No integrationSystemType: the field is WRITE_ONLY, so setting it here would write nothing.
                        // V105RevertMigration is what puts the type back for the legacy format.
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

    // The column is nullable, so a legacy row can reach this point with no type. Failing here names the row; letting it
    // through produced an NPE in EntityType.getSystemType once the export was already half written.
    private static IntegrationSystemType requireType(IntegrationSystem system) {
        IntegrationSystemType type = system.getIntegrationSystemType();
        if (type == null) {
            throw new ServiceExportException(
                    ("Service %s has no type, and an exported service states its type in its $schema. Set the type of"
                            + " the service, then export again. This service is left out of the archive; the rest of it"
                            + " is produced.").formatted(system.getId()));
        }
        return type;
    }
}
