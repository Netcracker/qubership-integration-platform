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

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.EnvironmentSetUpException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SystemDeleteException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystemLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemSearchRequestDTO;
import org.qubership.integration.platform.runtime.catalog.service.filter.SystemFilterSpecificationBuilder;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.CONNECT_TIMEOUT;

@Slf4j
@Service
public class SystemService extends SystemBaseService {

    public static final String SYSTEM_WITH_ID_NOT_FOUND_MESSAGE = "Can't find system with id: ";

    private final SystemModelService systemModelService;
    private final SystemFilterSpecificationBuilder systemFilterSpecificationBuilder;
    private final ElementHelperService elementHelperService;
    private final IntegrationSystemLabelsRepository systemLabelsRepository;

    @Autowired
    public SystemService(
            SystemRepository systemRepository,
            ActionsLogService actionsLogger,
            IntegrationSystemLabelsRepository systemLabelsRepository,
            SystemModelService systemModelService,
            SystemFilterSpecificationBuilder systemFilterSpecificationBuilder,
            ElementHelperService elementHelperService,
            IntegrationSystemLabelsRepository systemLabelsRepository1
    ) {
        super(systemRepository, actionsLogger, systemLabelsRepository);
        this.systemModelService = systemModelService;
        this.systemFilterSpecificationBuilder = systemFilterSpecificationBuilder;
        this.elementHelperService = elementHelperService;
        this.systemLabelsRepository = systemLabelsRepository1;
    }

    @Transactional
    public List<IntegrationSystem> findSystemsRequiredGatewayRoutes(Collection<String> systemIds) {
        return systemRepository.findAllById(systemIds)
                .stream()
                .filter(this::shouldCallControlPlane)
                .collect(Collectors.toList());
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

    @Transactional
    public Optional<IntegrationSystem> deleteByIdAndReturnService(String systemId) {
        IntegrationSystem system = getByIdOrNull(systemId);
        if (system != null) {
            if (elementHelperService.isSystemUsedByElement(systemId)) {
                throw new IllegalArgumentException("System used by one or more chains");
            }

            systemRepository.delete(system);
            logSystemAction(system, LogOperation.DELETE);
            return Optional.of(system);
        }
        return Optional.empty();
    }

    private boolean shouldCallControlPlane(IntegrationSystem system) {
        return StringUtils.isNotEmpty(system.getActiveEnvironmentId())
               && IntegrationSystemType.EXTERNAL.equals(system.getIntegrationSystemType())
               && (OperationProtocol.HTTP.equals(system.getProtocol())
                   || OperationProtocol.SOAP.equals(system.getProtocol())
                   || OperationProtocol.GRAPHQL.equals(system.getProtocol())
               );
    }

    protected Environment getActiveEnvironment(IntegrationSystem system) {
        return system.getEnvironments() != null ? system.getEnvironments()
                .stream()
                .filter(env -> system.getActiveEnvironmentId().equals(env.getId()))
                .findFirst()
                .orElse(null) : null;
    }

    protected String getActiveEnvAddress(Environment environment) throws EnvironmentSetUpException {
        String address = environment != null ? environment.getAddress() : null;

        if (StringUtils.isNotEmpty(address)) {
            return address;
        }
        throw new EnvironmentSetUpException();
    }

    protected Long getConnectTimeout(Environment activeEnvironment) {
        return activeEnvironment != null && activeEnvironment.getProperties().get(CONNECT_TIMEOUT) != null
                ? activeEnvironment.getProperties().get(CONNECT_TIMEOUT).asLong(120000L)
                : 120000L;

    }

    @Transactional
    public IntegrationSystem findById(String systemId) {
        return systemRepository.findById(systemId)
                .orElseThrow(() -> new EntityNotFoundException(SYSTEM_WITH_ID_NOT_FOUND_MESSAGE + systemId));
    }

    @Async
    public void updateSystemModelCompiledLibraryAsync(IntegrationSystem system) {
        systemModelService.updateCompiledLibrariesForSystem(system.getId());
    }

    @Override
    @Transactional
    public void delete(String systemId) {
        if (elementHelperService.isSystemUsedByElement(systemId)) {
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
