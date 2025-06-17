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

import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Service
public class SystemBaseService {

    private static final Map<IntegrationSystemType, Collection<OperationProtocol>> ALLOWED_PROTOCOL_MAP = Map.of(
            IntegrationSystemType.EXTERNAL, Arrays.stream(OperationProtocol.values())
                    .filter(protocol -> !OperationProtocol.METAMODEL.equals(protocol))
                    .collect(Collectors.toSet()),
            IntegrationSystemType.INTERNAL, Set.of(OperationProtocol.values()),
            IntegrationSystemType.IMPLEMENTED, Set.of(
                    OperationProtocol.HTTP,
                    OperationProtocol.SOAP,
                    OperationProtocol.GRAPHQL
            )
    );

    protected final SystemRepository systemRepository;
    protected final ActionsLogService actionsLogger;
    protected final IntegrationSystemLabelsRepository systemLabelsRepository;

    @Autowired
    public SystemBaseService(SystemRepository systemRepository,
                             ActionsLogService actionsLogger,
                             IntegrationSystemLabelsRepository systemLabelsRepository) {
        this.systemRepository = systemRepository;
        this.actionsLogger = actionsLogger;
        this.systemLabelsRepository = systemLabelsRepository;
    }

    @Transactional
    public List<IntegrationSystem> getAll() {
        return systemRepository.findAll(Sort.by("name"));
    }

    @Transactional
    public IntegrationSystem getByIdOrNull(String id) {
        return systemRepository.findById(id).orElse(null);
    }

    @Transactional
    public IntegrationSystem save(IntegrationSystem system) {
        return update(system);
    }

    @Transactional
    public IntegrationSystem create(IntegrationSystem system) {
        return create(system, false);
    }

    @Transactional
    public IntegrationSystem create(IntegrationSystem system, boolean isImport) {
        IntegrationSystem savedSystem = systemRepository.save(system);
        logSystemAction(savedSystem, isImport ? LogOperation.CREATE_OR_UPDATE : LogOperation.CREATE);
        return savedSystem;
    }

    @Transactional
    public IntegrationSystem update(IntegrationSystem system) {
        return update(system, true);
    }

    @Transactional
    public IntegrationSystem update(IntegrationSystem system, boolean logAction) {
        IntegrationSystem updatedSystem = systemRepository.save(system);
        if (logAction) {
            logSystemAction(updatedSystem, LogOperation.UPDATE);
        }
        return updatedSystem;
    }

    @Transactional
    public void delete(String systemId) {
        IntegrationSystem system = systemRepository.getReferenceById(systemId);
        systemRepository.delete(system);
        logSystemAction(system, LogOperation.DELETE);
    }

    @Transactional
    public void validateSpecificationProtocol(IntegrationSystem system, OperationProtocol protocol) {
        if (isNull(protocol)) {
            return;
        }
        IntegrationSystemType systemType = system.getIntegrationSystemType();
        if (!ALLOWED_PROTOCOL_MAP.getOrDefault(systemType, Collections.emptyList()).contains(protocol)) {
            String message = String.format("Specification type is not allowed for %s system: %s",
                    systemType.name().toLowerCase(), protocol.getType());
            throw new RuntimeException(message);
        }
    }

    protected void logSystemAction(IntegrationSystem system, LogOperation operation) {
        actionsLogger.logAction(ActionLog.builder()
                .entityType(EntityType.getSystemType(system))
                .entityId(system.getId())
                .entityName(system.getName())
                .operation(operation)
                .build());
    }
}
