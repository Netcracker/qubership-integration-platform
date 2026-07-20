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

package org.qubership.integration.platform.io.readers.system;

import org.qubership.integration.platform.chain.impl.ImportEnvironmentImpl;
import org.qubership.integration.platform.chain.impl.ImportOperationImpl;
import org.qubership.integration.platform.chain.impl.ImportSpecificationGroupImpl;
import org.qubership.integration.platform.chain.impl.ImportSpecificationSourceImpl;
import org.qubership.integration.platform.chain.impl.ImportSystemImpl;
import org.qubership.integration.platform.chain.impl.ImportSystemModelImpl;
import org.qubership.integration.platform.chain.model.ImportEnvironment;
import org.qubership.integration.platform.chain.model.ImportOperation;
import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.EnvironmentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.io.model.exportimport.system.OperationDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationSourceDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelDto;

import java.util.List;

/**
 * Maps the deserialized system export DTOs to the library import model.
 *
 * <p>The DTOs are the serialization format the reader deserializes; the model is what the catalog
 * consumes to rebuild its JPA entities. This mapper is the boundary between the two: it flattens
 * each DTO's {@code content} block onto the corresponding model node and rebuilds the child lists.
 * Specification-source text is not part of a DTO — it lives in a separate archive file — so
 * {@link ImportSpecificationSource#getSource()} stays empty here and the reader fills it from disk.
 */
public final class SystemImportModelMapper {

    private SystemImportModelMapper() {
    }

    public static ImportSystem toModel(IntegrationSystemDto dto) {
        ImportSystemImpl model = new ImportSystemImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());

        IntegrationSystemContentDto content = dto.getContent();
        if (content != null) {
            model.setDescription(content.getDescription());
            model.setCreatedBy(content.getCreatedBy());
            model.setCreatedWhen(content.getCreatedWhen());
            model.setModifiedBy(content.getModifiedBy());
            model.setModifiedWhen(content.getModifiedWhen());
            model.setActiveEnvironmentId(content.getActiveEnvironmentId());
            model.setIntegrationSystemType(content.getIntegrationSystemType());
            model.setInternalServiceName(content.getInternalServiceName());
            model.setProtocol(content.getProtocol());
            model.setEnvironments(toEnvironments(content.getEnvironments()));
            model.setLabels(content.getLabels());
            model.setSpecificationGroups(toSpecificationGroups(content.getSpecificationGroups()));
        }
        return model;
    }

    public static ImportSpecificationGroup toModel(SpecificationGroupDto dto) {
        ImportSpecificationGroupImpl model = new ImportSpecificationGroupImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());

        SpecificationGroupContentDto content = dto.getContent();
        if (content != null) {
            model.setDescription(content.getDescription());
            model.setCreatedBy(content.getCreatedBy());
            model.setCreatedWhen(content.getCreatedWhen());
            model.setModifiedBy(content.getModifiedBy());
            model.setModifiedWhen(content.getModifiedWhen());
            model.setUrl(content.getUrl());
            model.setSynchronization(content.isSynchronization());
            model.setParentId(content.getParentId());
            model.setLabels(content.getLabels());
        }
        return model;
    }

    public static ImportSystemModel toModel(SystemModelDto dto) {
        ImportSystemModelImpl model = new ImportSystemModelImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());

        SystemModelContentDto content = dto.getContent();
        if (content != null) {
            model.setDescription(content.getDescription());
            model.setCreatedBy(content.getCreatedBy());
            model.setCreatedWhen(content.getCreatedWhen());
            model.setModifiedBy(content.getModifiedBy());
            model.setModifiedWhen(content.getModifiedWhen());
            model.setDeprecated(content.isDeprecated());
            model.setVersion(content.getVersion());
            model.setSource(content.getSource());
            model.setOperations(toOperations(content.getOperations()));
            model.setParentId(content.getParentId());
            model.setLabels(content.getLabels());
            model.setSpecificationSources(toSpecificationSources(content.getSpecificationSources()));
        }
        return model;
    }

    public static ImportSpecificationSource toModel(SpecificationSourceDto dto) {
        ImportSpecificationSourceImpl model = new ImportSpecificationSourceImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());
        model.setDescription(dto.getDescription());
        model.setCreatedBy(dto.getCreatedBy());
        model.setCreatedWhen(dto.getCreatedWhen());
        model.setModifiedBy(dto.getModifiedBy());
        model.setModifiedWhen(dto.getModifiedWhen());
        model.setSourceHash(dto.getSourceHash());
        model.setFileName(dto.getFileName());
        model.setMainSource(dto.isMainSource());
        return model;
    }

    public static ImportEnvironment toModel(EnvironmentDto dto) {
        ImportEnvironmentImpl model = new ImportEnvironmentImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());
        model.setDescription(dto.getDescription());
        model.setAddress(dto.getAddress());
        model.setSourceType(dto.getSourceType());
        model.setLabels(dto.getLabels());
        model.setMaasInstanceId(dto.getMaasInstanceId());
        model.setProperties(dto.getProperties());
        return model;
    }

    public static ImportOperation toModel(OperationDto dto) {
        ImportOperationImpl model = new ImportOperationImpl();
        model.setId(dto.getId());
        model.setName(dto.getName());
        model.setDescription(dto.getDescription());
        model.setMethod(dto.getMethod());
        model.setPath(dto.getPath());
        model.setSpecification(dto.getSpecification());
        model.setRequestSchema(dto.getRequestSchema());
        model.setResponseSchemas(dto.getResponseSchemas());
        return model;
    }

    private static List<ImportEnvironment> toEnvironments(List<EnvironmentDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(SystemImportModelMapper::toModel).toList();
    }

    private static List<ImportSpecificationGroup> toSpecificationGroups(List<SpecificationGroupDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(SystemImportModelMapper::toModel).toList();
    }

    private static List<ImportOperation> toOperations(List<OperationDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(SystemImportModelMapper::toModel).toList();
    }

    private static List<ImportSpecificationSource> toSpecificationSources(List<SpecificationSourceDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(SystemImportModelMapper::toModel).toList();
    }
}
