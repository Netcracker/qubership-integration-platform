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

package org.qubership.integration.platform.runtime.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.exception.SpecificationImportException;
import org.qubership.integration.platform.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SpecificationGroupLabelsRepository;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.catalog.service.AbstractSpecificationGroupService;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.exportimport.ProtocolExtractionService;
import org.qubership.integration.platform.catalog.util.MultipartFileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.DIFFERENT_PROTOCOL_ERROR_MESSAGE;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.INVALID_INPUT_FILE_ERROR_MESSAGE;

@Slf4j
@Service
public class SpecificationGroupService extends AbstractSpecificationGroupService {

    private final SystemService systemService;
    private final ProtocolExtractionService protocolExtractionService;
    private final ChainService chainService;

    @Autowired
    public SpecificationGroupService(
            SpecificationGroupRepository specificationGroupRepository,
            ActionsLogService actionLogger,
            SystemService systemService,
            ProtocolExtractionService protocolExtractionService,
            SpecificationGroupLabelsRepository specificationGroupLabelsRepository,
            ChainService chainService
    ) {
        super(specificationGroupRepository, actionLogger, specificationGroupLabelsRepository);
        this.systemService = systemService;
        this.protocolExtractionService = protocolExtractionService;
        this.chainService = chainService;
    }

    public SpecificationGroup createAndSaveSpecificationGroupWithProtocol(IntegrationSystem system,
                                                                          String specificationName,
                                                                          String protocol,
                                                                          MultipartFile[] files,
                                                                          String specificationUrl) {
        if (system == null) {
            throw new SpecificationImportException(SYSTEM_NOT_FOUND_ERROR_MESSAGE);
        }
        if (specificationGroupRepository.findByNameAndSystem(specificationName, system) != null) {
            throw new SpecificationImportException(SPECIFICATION_GROUP_NAME_ERROR_MESSAGE);
        } else {
            setSystemProtocol(system, protocol, files);

            SpecificationGroup specificationGroup = new SpecificationGroup();
            specificationGroup.setId(buildSpecificationGroupId(system, specificationName));
            specificationGroup.setName(specificationName);
            specificationGroup.setUrl(specificationUrl);

            specificationGroup = specificationGroupRepository.save(specificationGroup);

            system.addSpecificationGroup(specificationGroup);

            systemService.update(system, false);

            logSpecGroupAction(specificationGroup, system, LogOperation.CREATE);
            return specificationGroup;
        }
    }

    public SpecificationGroup createAndSaveSpecificationGroup(String systemId,
                                                              String specificationName,
                                                              String protocol,
                                                              MultipartFile[] files) {
        return createAndSaveSpecificationGroupWithProtocol(systemService.getByIdOrNull(systemId), specificationName, protocol, files, null);
    }

    public Optional<SpecificationGroup> deleteByIdExists(String specificationGroupId) {
        Optional<SpecificationGroup> specificationGroupOptional = specificationGroupRepository.findById(specificationGroupId);
        if (specificationGroupOptional.isPresent()) {
            if (chainService.isSpecificationGroupUsedByChain(specificationGroupId)) {
                throw new IllegalArgumentException("Specification group used by one or more chains");
            }

            SpecificationGroup specificationGroup = specificationGroupOptional.get();
            specificationGroupRepository.delete(specificationGroup);
            logSpecGroupAction(specificationGroup, specificationGroup.getSystem(), LogOperation.DELETE);
            return Optional.of(specificationGroup);
        }

        return Optional.empty();
    }

    private void setSystemProtocol(IntegrationSystem system, String protocol, MultipartFile[] files) {
        OperationProtocol operationProtocol;

        try {
            if (system.getProtocol() == null) {
                if (StringUtils.isBlank(protocol)) {
                    operationProtocol = protocolExtractionService.getOperationProtocol(MultipartFileUtils.extractArchives(files));
                } else {
                    operationProtocol = OperationProtocol.fromValue(protocol);
                }

                if (isNull(operationProtocol)) {
                    throw new SpecificationImportException("Unsupported protocol: " + protocol);
                } else {
                    systemService.validateSpecificationProtocol(system, operationProtocol);
                    system.setProtocol(operationProtocol);
                }
            } else {
                operationProtocol = protocolExtractionService.getOperationProtocol(MultipartFileUtils.extractArchives(files));

                if (operationProtocol != null && !system.getProtocol().equals(operationProtocol)) {
                    throw new SpecificationImportException(DIFFERENT_PROTOCOL_ERROR_MESSAGE);
                }
            }
        } catch (IOException exception) {
            throw new SpecificationImportException(INVALID_INPUT_FILE_ERROR_MESSAGE, exception);
        }
    }
}
