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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ImportFileMigrationUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.ARCH_PARENT_DIR;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SPECIFICATION_SOURCE_FILE_NAME;


@Component
public class ServiceSerializer {

    private final YAMLMapper yamlMapper;
    private final ExportableObjectWriterVisitor exportableObjectWriterVisitor;

    @Autowired
    public ServiceSerializer(YAMLMapper yamlExportImportMapper,
                             ExportableObjectWriterVisitor exportableObjectWriterVisitor) {
        this.yamlMapper = yamlExportImportMapper;
        this.exportableObjectWriterVisitor = exportableObjectWriterVisitor;
    }

    public ExportedSystemObject serialize(IntegrationSystem system) throws JsonProcessingException {
        ObjectNode systemNode = yamlMapper.valueToTree(system);

        List<ExportedSpecificationGroup> exportedSpecificationGroups = new ArrayList<>();
        for (SpecificationGroup specificationGroup : system.getSpecificationGroups()) {
            List<ExportedSpecification> exportedSpecifications = new ArrayList<>();
            for (SystemModel specification : specificationGroup.getSystemModels()) {
                exportedSpecifications.add(serialize(specification));
            }

            exportedSpecificationGroups.add(new ExportedSpecificationGroup(
                    specificationGroup.getId(), yamlMapper.valueToTree(specificationGroup), exportedSpecifications));
        }

        provideFileAdditionalData(systemNode);

        return new ExportedIntegrationSystem(system.getId(), systemNode, exportedSpecificationGroups);
    }

    public ExportedSpecification serialize(SystemModel specification) {
        ObjectNode specificationNode = yamlMapper.valueToTree(specification);

        ArrayNode specificationSourcesNodes = null;

        // Adding specification source object manually so we can add filename (with full path)
        List<ExportedSpecificationSource> exportedSpecificationSources = new ArrayList<>();
        for (SpecificationSource source : specification.getSpecificationSources()) {
            exportedSpecificationSources.add(new ExportedSpecificationSource(source.getId(), source.getSource(),
                    ExportImportUtils.getFullSpecificationFileName(source)));

            ObjectNode specificationSourceNode = yamlMapper.valueToTree(source);
            specificationSourceNode.remove(AbstractSystemEntity.Fields.modifiedWhen);
            specificationSourceNode.put(SPECIFICATION_SOURCE_FILE_NAME, ExportImportUtils.getFullSpecificationFileName(source));
            if (specificationSourcesNodes == null) {
                specificationNode.putArray(SystemModel.Fields.specificationSources);
                specificationSourcesNodes = (ArrayNode) specificationNode.get(SystemModel.Fields.specificationSources);
            }
            specificationSourcesNodes.add(specificationSourceNode);
        }

        return new ExportedSpecification(specification.getId(), specificationNode, exportedSpecificationSources);
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

    private void provideFileAdditionalData(ObjectNode serviceNode) {
        serviceNode.put(
                ImportFileMigration.IMPORT_MIGRATIONS_FIELD,
                ImportFileMigrationUtils.getActualServiceFileMigrationVersions().stream()
                        .sorted()
                        .toList()
                        .toString());
    }
}
