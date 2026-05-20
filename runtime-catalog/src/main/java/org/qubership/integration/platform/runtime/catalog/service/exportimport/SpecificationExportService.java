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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import io.micrometer.core.instrument.util.StringUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.SpecificationGroupService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.FILE_CREATION_ERROR_MESSAGE;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.NO_SPECIFICATION_SOURCE_ERROR_MESSAGE;


@Service
@Slf4j
public class SpecificationExportService {
    private final SystemModelService systemModelService;
    private final SpecificationGroupService specificationGroupService;
    private final ActionsLogService actionLogger;

    @Autowired
    SpecificationExportService(SystemModelService systemModelService, SpecificationGroupService specificationGroupService,
                               ActionsLogService actionLogger) {
        this.systemModelService = systemModelService;
        this.specificationGroupService = specificationGroupService;
        this.actionLogger = actionLogger;
    }

    // TODO Rewrite to use main export service
    public Pair<byte[], String> exportSpecifications(List<String> specificationIds, String specificationGroupId) {
        try (ByteArrayOutputStream fos = new ByteArrayOutputStream()) {
            String filename;
            SystemModel singleSystemModel = null;

            if (specificationIds != null && specificationIds.size() == 1) {
                singleSystemModel = systemModelService.getSystemModelOrElseNull(specificationIds.get(0));
            }

            if (singleSystemModel != null && singleSystemModel.getSpecificationSources().size() == 1) {
                SpecificationSource specificationSource = singleSystemModel.getSpecificationSources().get(0);
                writeFile(fos, specificationSource.getSource());
                filename = ExportImportUtils.getSpecificationFileName(specificationSource);
                logSpecificationExport(singleSystemModel);
            } else {
                try (ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                    if (StringUtils.isNotBlank(specificationGroupId)) {
                        SpecificationGroup specificationGroup = specificationGroupService.getById(specificationGroupId);

                        for (SystemModel systemModel : specificationGroup.getSystemModels()) {
                            if (specificationIds == null || specificationIds.contains(systemModel.getId())) {
                                if (systemModel.getSpecificationSources().isEmpty()) {
                                    throw new EntityNotFoundException(NO_SPECIFICATION_SOURCE_ERROR_MESSAGE + " for version " + systemModel.getVersion());
                                }

                                ExportImportUtils.writeZip(zipOut, systemModel);
                                logSpecificationExport(systemModel);
                            }
                        }
                        filename = ExportImportUtils.generateArchiveExportName();
                    } else {
                        throw new EntityNotFoundException(NO_SPECIFICATION_SOURCE_ERROR_MESSAGE);
                    }
                }
            }

            return Pair.of(fos.toByteArray(), filename);
        } catch (IOException e) {
            throw new RuntimeException(FILE_CREATION_ERROR_MESSAGE + e.getMessage());
        }
    }

    private void writeFile(ByteArrayOutputStream fos, String source) {
        if (source == null) {
            throw new EntityNotFoundException(NO_SPECIFICATION_SOURCE_ERROR_MESSAGE);
        }
        byte[] sourceBytes = source.getBytes();
        fos.write(sourceBytes, 0, sourceBytes.length);
    }

    public void logSpecificationExport(SystemModel specification) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SPECIFICATION)
                .entityId(specification.getId())
                .entityName(specification.getName())
                .parentType(EntityType.SPECIFICATION_GROUP)
                .parentId(specification.getSpecificationGroup().getId())
                .parentName(specification.getSpecificationGroup().getName())
                .operation(LogOperation.EXPORT)
                .build());
    }
}
