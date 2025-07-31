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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.context.ContextSystemRepository;
import org.springframework.data.domain.Sort;

import java.util.List;

@Slf4j
public abstract class AbstractContextSystemService {

    public static final String CONTEXT_SYSTEM_WITH_ID_NOT_FOUND = "Can't find context system with id: ";

    protected final ContextSystemRepository contextSystemRepository;

    protected final ActionsLogService actionLogger;

    public AbstractContextSystemService(
            ContextSystemRepository contextSystemRepository,
            ActionsLogService actionLogger
    ) {
        this.contextSystemRepository = contextSystemRepository;
        this.actionLogger = actionLogger;
    }

    public ContextSystem save(ContextSystem databaseSystem) {
        return contextSystemRepository.save(databaseSystem);
    }

    public List<ContextSystem> findAll() {
        return contextSystemRepository.findAll(Sort.by("name"));
    }

    public ContextSystem findById(String systemId) {
        return contextSystemRepository.findById(systemId)
                .orElseThrow(() -> new EntityNotFoundException(CONTEXT_SYSTEM_WITH_ID_NOT_FOUND + systemId));
    }

    public ContextSystem getByIdOrNull(String systemId) {
        return contextSystemRepository.findById(systemId).orElse(null);
    }



    protected ContextSystem enrichAndSaveContextSystem(ContextSystem createdSystem, boolean isImport) {
        createdSystem = contextSystemRepository.save(createdSystem);
        if (log.isDebugEnabled()) {
            log.debug("Created database system: {}", createdSystem);
        }
        logContextSystemAction(createdSystem, isImport ? LogOperation.CREATE_OR_UPDATE : LogOperation.CREATE);
        return createdSystem;
    }

    protected void logContextSystemAction(ContextSystem contextSystem, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.CONTEXT_SYSTEM)
                .entityId(contextSystem.getId())
                .entityName(contextSystem.getName())
                .parentId(null)
                .operation(operation)
                .build());
    }
}

