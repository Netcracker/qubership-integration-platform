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

import org.qubership.integration.platform.chain.model.ImportEnvironment;
import org.qubership.integration.platform.chain.model.ImportOperation;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.io.model.exportimport.system.EnvironmentDto;
import org.qubership.integration.platform.io.model.exportimport.system.OperationDto;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;

import java.util.List;

/**
 * Converts the library import model and export value types to and from their persistence
 * counterparts.
 *
 * <p>The library owns the import model ({@code ImportEnvironment}, {@code ImportOperation},
 * {@code ImportSpecificationSource}), the export format and its serialization value types
 * ({@code EnvironmentDto} / {@code OperationDto}), the shared {@code User} value, and the system
 * enums; the catalog owns the JPA entities. This is the single seam where the service mappers
 * translate one side into the other: the model on import, the DTOs on export, by enum name for the
 * enums and field for field for everything else.
 *
 * <p>The library also owns the parser output model ({@code ParsedOperation}). This seam maps it to
 * and from the {@code Operation} entity so the catalog can persist what a parser produces.
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

    public static Environment toPersistenceEnvironment(ImportEnvironment environment) {
        if (environment == null) {
            return null;
        }
        List<EnvironmentLabel> labels = environment.getLabels() == null
                ? null
                : environment.getLabels().stream().map(EnvironmentLabel::valueOf).toList();
        return Environment.builder()
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

    public static Operation toPersistenceOperation(ImportOperation operation) {
        if (operation == null) {
            return null;
        }
        return Operation.builder()
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

    /**
     * Maps a parsed operation to its persistence entity. The parser assigns no identity, so the
     * caller sets the id after mapping.
     */
    public static Operation toPersistenceOperation(ParsedOperation operation) {
        if (operation == null) {
            return null;
        }
        return Operation.builder()
                .name(operation.getName())
                .method(operation.getMethod())
                .path(operation.getPath())
                .specification(operation.getSpecification())
                .requestSchema(operation.getRequestSchema())
                .responseSchemas(operation.getResponseSchemas())
                .build();
    }

    /**
     * Maps a specification source to its persistence entity. The source text is not part of the
     * model; the catalog reads it from the archive file and passes it as {@code sourceContent}. It
     * is set through the builder rather than {@code setSource} so the exported {@code sourceHash} is
     * preserved instead of recomputed.
     */
    public static SpecificationSource toPersistenceSpecificationSource(
            ImportSpecificationSource source, String sourceContent) {
        if (source == null) {
            return null;
        }
        return SpecificationSource.builder()
                .id(source.getId())
                .name(source.getName())
                .description(source.getDescription())
                .createdBy(toPersistenceUser(source.getCreatedBy()))
                .createdWhen(source.getCreatedWhen())
                .modifiedBy(toPersistenceUser(source.getModifiedBy()))
                .modifiedWhen(source.getModifiedWhen())
                .sourceHash(source.getSourceHash())
                .isMainSource(source.isMainSource())
                .source(sourceContent)
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
