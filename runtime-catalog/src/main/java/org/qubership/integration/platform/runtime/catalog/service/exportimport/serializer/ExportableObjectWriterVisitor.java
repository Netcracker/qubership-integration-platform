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

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedIntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecificationSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.zip.ZipOutputStream;

@Slf4j
@Component
public class ExportableObjectWriterVisitor {

    private final YAMLMapper yamlMapper;

    @Autowired
    public ExportableObjectWriterVisitor(YAMLMapper yamlExportImportMapper) {
        this.yamlMapper = yamlExportImportMapper;
    }

    public void visit(ExportedIntegrationSystem exportedIntegrationSystem, ZipOutputStream zipOut, String entryPath) throws IOException {
        ExportImportUtils.writeSystemObject(zipOut,
                entryPath + ExportImportUtils.generateMainSystemFileExportName(exportedIntegrationSystem.getId()),
                yamlMapper.writeValueAsString(exportedIntegrationSystem.getObjectNode()));
    }

    public void visit(ExportedSpecificationGroup exportedSpecificationGroup, ZipOutputStream zipOut, String entryPath) throws IOException {
        ExportImportUtils.writeSystemObject(zipOut,
                entryPath
                        + ExportImportUtils.generateSpecificationGroupFileExportName(exportedSpecificationGroup.getId()),
                yamlMapper.writeValueAsString(exportedSpecificationGroup.getObjectNode()));
    }

    public void visit(ExportedSpecification exportedSpecification, ZipOutputStream zipOut, String entryPath) throws IOException {
        ExportImportUtils.writeSystemObject(zipOut,
                entryPath
                        + ExportImportUtils.generateSpecificationFileExportName(exportedSpecification.getId()),
                yamlMapper.writeValueAsString(exportedSpecification.getObjectNode()));
    }

    public void visit(ExportedSpecificationSource exportedSpecificationSource, ZipOutputStream zipOut, String entryPath) throws IOException {
        if (exportedSpecificationSource.getSource() == null) {
            log.warn("Can't find source for specification {}", exportedSpecificationSource.getId());
            return;
        }

        ExportImportUtils.writeSystemObject(zipOut, entryPath + exportedSpecificationSource.getName(),
                exportedSpecificationSource.getSource());
    }
}
