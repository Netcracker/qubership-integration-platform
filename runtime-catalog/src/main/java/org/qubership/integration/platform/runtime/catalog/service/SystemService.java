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
import org.qubership.integration.platform.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SystemRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.SystemBaseService;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.EnvironmentSetUpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.CONNECT_TIMEOUT;

@Slf4j
@Service
public class SystemService extends SystemBaseService {

    public static final String SYSTEM_WITH_ID_NOT_FOUND_MESSAGE = "Can't find system with id: ";

    private final ChainService chainService;

    @Autowired
    public SystemService(
            SystemRepository systemRepository,
            ActionsLogService actionsLogger,
            IntegrationSystemLabelsRepository systemLabelsRepository,
            @Lazy ChainService chainService
    ) {
        super(systemRepository, actionsLogger, systemLabelsRepository);
        this.chainService = chainService;
    }

    @Transactional
    public List<IntegrationSystem> findSystemsRequiredGatewayRoutes(Collection<String> systemIds) {
        return systemRepository.findAllById(systemIds)
                .stream()
                .filter(this::shouldCallControlPlane)
                .collect(Collectors.toList());
    }

    public Optional<IntegrationSystem> deleteByIdAndReturnService(String systemId) {
        IntegrationSystem system = getByIdOrNull(systemId);
        if (system != null) {
            if (chainService.isSystemUsedByChain(systemId)) {
                throw new IllegalArgumentException("System used by one or more chains");
            }

            systemRepository.delete(system);
            logSystemAction(system, LogOperation.DELETE);
            return Optional.of(system);
        }
        return Optional.empty();
    }

    private boolean shouldCallControlPlane(IntegrationSystem system) {
        return StringUtils.isNotEmpty(system.getActiveEnvironmentId()) &&
                IntegrationSystemType.EXTERNAL.equals(system.getIntegrationSystemType()) &&
                (
                        OperationProtocol.HTTP.equals(system.getProtocol()) ||
                                OperationProtocol.SOAP.equals(system.getProtocol()) ||
                                OperationProtocol.GRAPHQL.equals(system.getProtocol())
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
}
