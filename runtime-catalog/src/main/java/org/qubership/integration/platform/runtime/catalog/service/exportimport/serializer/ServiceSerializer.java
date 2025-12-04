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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SpecificationGroupDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SpecificationGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.ARCH_PARENT_DIR;


@Component
public class ServiceSerializer {

    private final YAMLMapper yamlMapper;
    private final ExportableObjectWriterVisitor exportableObjectWriterVisitor;
    private final IntegrationSystemDtoMapper integrationSystemDtoMapper;
    private final SpecificationGroupDtoMapper specificationGroupDtoMapper;
    private final SystemModelDtoMapper systemModelDtoMapper;

    @Autowired
    public ServiceSerializer(
            YAMLMapper yamlExportImportMapper,
            ExportableObjectWriterVisitor exportableObjectWriterVisitor,
            IntegrationSystemDtoMapper integrationSystemDtoMapper,
            SpecificationGroupDtoMapper specificationGroupDtoMapper,
            SystemModelDtoMapper systemModelDtoMapper
    ) {
        this.yamlMapper = yamlExportImportMapper;
        this.exportableObjectWriterVisitor = exportableObjectWriterVisitor;
        this.integrationSystemDtoMapper = integrationSystemDtoMapper;
        this.specificationGroupDtoMapper = specificationGroupDtoMapper;
        this.systemModelDtoMapper = systemModelDtoMapper;
    }

    public ExportedSystemObject serialize(IntegrationSystem system) {
        IntegrationSystemDto integrationSystemDto = integrationSystemDtoMapper.toExternalEntity(system);
        ObjectNode systemNode = yamlMapper.valueToTree(integrationSystemDto);

        List<ExportedSpecificationGroup> exportedSpecificationGroups = system.getSpecificationGroups()
                .stream()
                .map(this::serialize)
                .toList();

        return new ExportedIntegrationSystem(system.getId(), systemNode, exportedSpecificationGroups);
    }

    public ExportedSpecificationGroup serialize(SpecificationGroup specificationGroup) {
        SpecificationGroupDto dto = specificationGroupDtoMapper.toExternalEntity(specificationGroup);
        ObjectNode node = yamlMapper.valueToTree(dto);
        List<ExportedSpecification> exportedSpecifications = specificationGroup.getSystemModels()
                .stream()
                .map(this::serialize)
                .toList();
        return new ExportedSpecificationGroup(specificationGroup.getId(), node, exportedSpecifications);
    }

    public ExportedSpecification serialize(SystemModel specification) {
        SystemModelDto dto = systemModelDtoMapper.toExternalEntity(specification);
        ObjectNode node = yamlMapper.valueToTree(dto);

        List<ExportedSpecificationSource> exportedSpecificationSources = specification.getSpecificationSources()
                .stream()
                .map(source -> new ExportedSpecificationSource(
                        source.getId(),
                        source.getSource(),
                        ExportImportUtils.getFullSpecificationFileName(source)))
                .toList();

        return new ExportedSpecification(specification.getId(), node, exportedSpecificationSources);
    }

    public byte[] writeSerializedArchive(List<ExportedSystemObject> exportedSystems) {
        try (ByteArrayOutputStream fos = new ByteArrayOutputStream()) {
            try (ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                for (ExportedSystemObject exportedSystem : exportedSystems) {
                    String entryPath = ARCH_PARENT_DIR + File.separator + exportedSystem.getId() + File.separator;
                    exportedSystem.accept(exportableObjectWriterVisitor, zipOut, entryPath);
                }
            }
            return fos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unknown exception while archive creation: " + e.getMessage(), e);
        }
    }
}
