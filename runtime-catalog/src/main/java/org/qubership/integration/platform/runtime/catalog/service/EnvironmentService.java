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
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.EnvironmentRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@Transactional
public class EnvironmentService extends EnvironmentBaseService {

    private static final String ENVIRONMENT_WITH_ID_NOT_FOUND_MESSAGE = "Can't find environment with id ";

    @Autowired
    public EnvironmentService(EnvironmentRepository environmentRepository,
                              ActionsLogService actionLogger,
                              SystemService systemService,
                              ParserUtils parserUtils,
                              @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper) {
        super(environmentRepository, systemService, actionLogger, jsonMapper, parserUtils);
    }

    public Environment getByIdForSystem(String systemId, String environmentId) {
        return environmentRepository.findBySystemIdAndId(systemId, environmentId)
                .orElseThrow(() -> new EntityNotFoundException(ENVIRONMENT_WITH_ID_NOT_FOUND_MESSAGE + environmentId));
    }

    public List<Environment> getEnvironmentsForSystem(String systemId) {
        return environmentRepository.findAllBySystemId(systemId);
    }

    public Environment getByIdForSystemOrElseNull(String systemId, String environmentId) {
        return environmentRepository.findBySystemIdAndId(systemId, environmentId)
                .orElse(null);
    }

    public List<Environment> getEnvironmentsByLabel(String systemId, EnvironmentLabel label) {
        return environmentRepository.findAllBySystemIdAndLabelsContains(systemId, label);
    }

    public void deleteEnvironment(String systemId, String environmentId) {
        Environment oldEnvironment = getByIdForSystem(systemId, environmentId);
        IntegrationSystem system = oldEnvironment.getSystem();
        system.removeEnvironment(oldEnvironment);
        environmentRepository.delete(oldEnvironment);

        logEnvironmentAction(oldEnvironment, system, LogOperation.DELETE);
    }
}
