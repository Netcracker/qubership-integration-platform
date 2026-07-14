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

import org.qubership.integration.platform.io.model.exportimport.system.EnvironmentDto;
import org.qubership.integration.platform.io.model.exportimport.system.OperationDto;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;

import java.util.List;

/**
 * Converts the library import/export value types to and from their persistence counterparts.
 *
 * <p>The library owns the export format and its value types ({@code User}, the system enums, and the
 * {@code EnvironmentDto} / {@code OperationDto} serialization models); the catalog owns the JPA
 * entities. This is the single seam where the service mappers translate one side into the other, by
 * enum name for the enums and field for field for the value types.
 */
public final class SystemEntitySeam {

    private SystemEntitySeam() {
    }

    public static User toPersistenceUser(org.qubership.integration.platform.io.model.exportimport.system.User user) {
        if (user == null) {
            return null;
        }
        return User.builder()
                .id(user.getId())
                .username(user.getUsername())
                .build();
    }

    public static org.qubership.integration.platform.io.model.exportimport.system.User toModelUser(User user) {
        if (user == null) {
            return null;
        }
        return org.qubership.integration.platform.io.model.exportimport.system.User.builder()
                .id(user.getId())
                .username(user.getUsername())
                .build();
    }

    public static IntegrationSystemType toPersistenceType(
            org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType type) {
        return type == null ? null : IntegrationSystemType.valueOf(type.name());
    }

    public static org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType toModelType(
            IntegrationSystemType type) {
        return type == null
                ? null
                : org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType.valueOf(type.name());
    }

    public static OperationProtocol toPersistenceProtocol(
            org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol protocol) {
        return protocol == null ? null : OperationProtocol.valueOf(protocol.name());
    }

    public static org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol toModelProtocol(
            OperationProtocol protocol) {
        return protocol == null
                ? null
                : org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol.valueOf(protocol.name());
    }

    public static SystemModelSource toPersistenceSource(
            org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource source) {
        return source == null ? null : SystemModelSource.valueOf(source.name());
    }

    public static org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource toModelSource(
            SystemModelSource source) {
        return source == null
                ? null
                : org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource.valueOf(source.name());
    }

    public static Environment toPersistenceEnvironment(EnvironmentDto dto) {
        if (dto == null) {
            return null;
        }
        List<EnvironmentLabel> labels = dto.getLabels() == null
                ? null
                : dto.getLabels().stream().map(EnvironmentLabel::valueOf).toList();
        return Environment.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .address(dto.getAddress())
                .sourceType(dto.getSourceType())
                .labels(labels)
                .maasInstanceId(dto.getMaasInstanceId())
                .properties(dto.getProperties())
                .build();
    }

    public static EnvironmentDto toModelEnvironment(Environment environment) {
        if (environment == null) {
            return null;
        }
        List<String> labels = environment.getLabels() == null
                ? null
                : environment.getLabels().stream().map(Enum::name).toList();
        return EnvironmentDto.builder()
                .id(environment.getId())
                .name(environment.getName())
                .description(environment.getDescription())
                .address(environment.getAddress())
                .sourceType(environment.getSourceType())
                .labels(labels)
                .maasInstanceId(environment.getMaasInstanceId())
                .properties(environment.getProperties())
                .build();
    }

    public static Operation toPersistenceOperation(OperationDto dto) {
        if (dto == null) {
            return null;
        }
        return Operation.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .method(dto.getMethod())
                .path(dto.getPath())
                .specification(dto.getSpecification())
                .requestSchema(dto.getRequestSchema())
                .responseSchemas(dto.getResponseSchemas())
                .build();
    }

    public static OperationDto toModelOperation(Operation operation) {
        if (operation == null) {
            return null;
        }
        return OperationDto.builder()
                .id(operation.getId())
                .name(operation.getName())
                .description(operation.getDescription())
                .method(operation.getMethod())
                .path(operation.getPath())
                .specification(operation.getSpecification())
                .requestSchema(operation.getRequestSchema())
                .responseSchemas(operation.getResponseSchemas())
                .build();
    }
}
