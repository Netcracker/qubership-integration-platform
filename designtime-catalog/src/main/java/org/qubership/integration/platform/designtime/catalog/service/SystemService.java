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

package org.qubership.integration.platform.designtime.catalog.service;

import org.qubership.integration.platform.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.IntegrationSystemLabel;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SystemRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.SystemBaseService;
import org.qubership.integration.platform.designtime.catalog.exception.exceptions.SystemDeleteException;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.system.SystemSearchRequestDTO;
import org.qubership.integration.platform.designtime.catalog.service.filter.SystemFilterSpecificationBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SystemService extends SystemBaseService {
    public static final String SYSTEM_WITH_ID_NOT_FOUND_MESSAGE = "Can't find system with id: ";

    private final SystemModelService systemModelService;
    private final SystemFilterSpecificationBuilder systemFilterSpecificationBuilder;
    private final ChainService chainService;
    private final IntegrationSystemLabelsRepository systemLabelsRepository;

    @Autowired
    public SystemService(SystemRepository systemRepository,
                         SystemModelService systemModelService,
                         IntegrationSystemLabelsRepository systemLabelsRepository,
                         ActionsLogService actionLogger,
                         SystemFilterSpecificationBuilder systemFilterSpecificationBuilder,
                         @Lazy ChainService chainService) {
        super(systemRepository, actionLogger, systemLabelsRepository);
        this.systemModelService = systemModelService;
        this.systemFilterSpecificationBuilder = systemFilterSpecificationBuilder;
        this.chainService = chainService;
        this.systemLabelsRepository = systemLabelsRepository;
    }

    @Transactional
    public List<IntegrationSystem> getNotDeprecatedWithSpecs() {
        return systemRepository.findAllByNotDeprecatedAndWithSpecs();
    }

    @Transactional
    public List<IntegrationSystem> getNotDeprecatedAndByModelType(List<OperationProtocol> modelType) {
        return systemRepository.findAllByNotDeprecatedAndWithSpecsAndModelType(modelType);
    }

    @Transactional
    public List<IntegrationSystem> getAllDiscoveredServices() {
        return systemRepository.findAllByInternalServiceNameNotNull();
    }

    @Transactional
    public List<IntegrationSystem> searchSystems(SystemSearchRequestDTO systemSearchRequestDTO) {
        return systemRepository.searchForSystems(systemSearchRequestDTO.getSearchCondition());
    }

    @Transactional
    public List<IntegrationSystem> findByFilterRequest(List<FilterRequestDTO> filters) {
        Specification<IntegrationSystem> specification = systemFilterSpecificationBuilder.buildFilter(filters);

        return systemRepository.findAll(specification);
    }

    @Async
    public void updateSystemModelCompiledLibraryAsync(IntegrationSystem system) {
        systemModelService.updateCompiledLibrariesForSystem(system.getId());
    }

    @Transactional
    public IntegrationSystem findById(String systemId) {
        return systemRepository.findById(systemId)
                .orElseThrow(() -> new EntityNotFoundException(SYSTEM_WITH_ID_NOT_FOUND_MESSAGE + systemId));
    }

    @Override
    @Transactional
    public void delete(String systemId) {
        if (chainService.isSystemUsedByChain(systemId)) {
            throw new SystemDeleteException("System used by one or more chains");
        }

        super.delete(systemId);
    }

    public void replaceLabels(IntegrationSystem system, List<IntegrationSystemLabel> newLabels) {
        if (newLabels == null) {
            return;
        }
        List<IntegrationSystemLabel> finalNewLabels = newLabels;
        final IntegrationSystem finalSystem = system;

        finalNewLabels.forEach(label -> label.setSystem(finalSystem));

        // Remove absent labels from db
        system.getLabels().removeIf(l -> !l.isTechnical() && !finalNewLabels.stream().map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));
        // Add to database only missing labels
        finalNewLabels.removeIf(l -> l.isTechnical() || finalSystem.getLabels().stream().filter(lab -> !lab.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));

        newLabels = systemLabelsRepository.saveAll(finalNewLabels);
        system.addLabels(newLabels);
    }
}
