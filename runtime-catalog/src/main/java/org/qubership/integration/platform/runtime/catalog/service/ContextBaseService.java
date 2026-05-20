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

import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.context.ContextSystemRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ContextBaseService {

    protected final ContextSystemRepository contextSystemRepository;
    protected final ActionsLogService actionsLogger;
    protected final IntegrationSystemLabelsRepository systemLabelsRepository;
    protected final ElementRepository elementRepository;

    @Autowired
    public ContextBaseService(ContextSystemRepository contextSystemRepository,
                              ActionsLogService actionsLogger,
                              IntegrationSystemLabelsRepository systemLabelsRepository,
                              ElementRepository elementRepository) {

        this.contextSystemRepository = contextSystemRepository;
        this.actionsLogger = actionsLogger;
        this.systemLabelsRepository = systemLabelsRepository;
        this.elementRepository = elementRepository;
    }

    @Transactional
    public List<ContextSystem> getAll() {
        return contextSystemRepository.findAll(Sort.by("name"));
    }

    @Transactional
    public ContextSystem getByIdOrNull(String id) {
        return contextSystemRepository.findById(id).orElse(null);
    }

    @Transactional
    public ContextSystem save(ContextSystem system) {
        return update(system);
    }

    @Transactional
    public ContextSystem create(ContextSystem system) {
        return create(system, false);
    }

    @Transactional
    public ContextSystem create(ContextSystem system, boolean isImport) {
        ContextSystem savedSystem = contextSystemRepository.save(system);
        logSystemAction(savedSystem, isImport ? LogOperation.CREATE_OR_UPDATE : LogOperation.CREATE);
        return savedSystem;
    }

    @Transactional
    public ContextSystem update(ContextSystem system) {
        return update(system, true);
    }

    @Transactional
    public ContextSystem update(ContextSystem system, boolean logAction) {
        ContextSystem updatedSystem = contextSystemRepository.save(system);
        if (logAction) {
            logSystemAction(updatedSystem, LogOperation.UPDATE);
        }
        return updatedSystem;
    }

    @Transactional
    public void delete(String systemId) {
        ContextSystem system = contextSystemRepository.getReferenceById(systemId);
        contextSystemRepository.delete(system);
        logSystemAction(system, LogOperation.DELETE);
    }

    public boolean isContextUsedByElement(String contextId) {
        return elementRepository.exists((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder
                                .function(
                                        "jsonb_extract_path_text",
                                        String.class,
                                        root.<String>get("properties"),
                                        builder.literal(CamelOptions.CONTEXT_SERVICE_ID)
                                ),
                        contextId)
        ));
    }


    protected void logSystemAction(ContextSystem system, LogOperation operation) {
        actionsLogger.logAction(ActionLog.builder()
                .entityType(EntityType.CONTEXT_SYSTEM)
                .entityId(system.getId())
                .entityName(system.getName())
                .operation(operation)
                .build());
    }
}
