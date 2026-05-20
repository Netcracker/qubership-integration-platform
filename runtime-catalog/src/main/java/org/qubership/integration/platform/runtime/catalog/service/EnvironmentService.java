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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.EnvironmentRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.mapping.ServiceEnvironmentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional
public class EnvironmentService extends EnvironmentBaseService {

    private static final String ENVIRONMENT_WITH_ID_NOT_FOUND_MESSAGE = "Can't find environment with id ";

    private final SystemService systemService;
    private final ServiceEnvironmentMapper serviceEnvironmentMapper;

    @Autowired
    public EnvironmentService(EnvironmentRepository environmentRepository,
                              ActionsLogService actionLogger,
                              SystemService systemService,
                              ServiceEnvironmentMapper serviceEnvironmentMapper,
                              ParserUtils parserUtils,
                              @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper) {
        super(environmentRepository, systemService, actionLogger, jsonMapper, parserUtils);
        this.serviceEnvironmentMapper = serviceEnvironmentMapper;
        this.systemService = systemService;
    }

    public Environment getByIdForSystem(String systemId, String environmentId) {
        return environmentRepository.findBySystemIdAndId(systemId, environmentId)
                .orElseThrow(() -> new EntityNotFoundException(ENVIRONMENT_WITH_ID_NOT_FOUND_MESSAGE + environmentId));
    }

    public List<Environment> getActiveEnvironmentsBySystems(List<IntegrationSystem> systems) {
        return systems.stream().map(system -> {
            List<Environment> systemEnvironments = system.getEnvironments();
            if (systemEnvironments == null || systemEnvironments.isEmpty()) {
                return null;
            }

            switch (system.getIntegrationSystemType()) {
                case INTERNAL:
                    return systemEnvironments.get(0);
                case IMPLEMENTED: {
                    String activeId = system.getActiveEnvironmentId();
                    return StringUtils.isBlank(activeId)
                            ? systemEnvironments.get(0)
                            : systemEnvironments.stream().filter(environment -> activeId.equals(environment.getId()))
                            .findAny().orElse(null);
                }
                case EXTERNAL: {
                    String activeId = system.getActiveEnvironmentId();
                    return StringUtils.isBlank(activeId)
                            ? null
                            : systemEnvironments.stream().filter(environment -> activeId.equals(environment.getId()))
                            .findAny().orElse(null);
                }
                default:
                    return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public void deleteEnvironment(String systemId, String environmentId) {
        Environment oldEnvironment = getByIdForSystem(systemId, environmentId);
        IntegrationSystem system = oldEnvironment.getSystem();
        system.removeEnvironment(oldEnvironment);
        environmentRepository.delete(oldEnvironment);

        logEnvironmentAction(oldEnvironment, system, LogOperation.DELETE);
    }

    public List<ServiceEnvironment> generateSystemEnvironments(Collection<String> ids) {
        List<IntegrationSystem> systems = ids.stream()
                .map(systemService::getByIdOrNull).filter(Objects::nonNull)
                .collect(Collectors.toList());
        List<ServiceEnvironment> result = getActiveEnvironmentsBySystems(systems).stream()
                .map(serviceEnvironmentMapper::toServiceEnvironment).collect(Collectors.toList());

        for (IntegrationSystem system : systems) {
            if (result.stream().anyMatch(environment -> environment.getSystemId().equals(system.getId()))) {
                continue;
            }
            ServiceEnvironment serviceEnvironment = new ServiceEnvironment();
            serviceEnvironment.setSystemId(system.getId());
            serviceEnvironment.setNotActivated(true);
            result.add(serviceEnvironment);
        }
        return result;
    }
}
