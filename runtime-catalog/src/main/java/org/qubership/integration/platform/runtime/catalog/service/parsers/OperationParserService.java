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

package org.qubership.integration.platform.runtime.catalog.service.parsers;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.context.RequestIdContext;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
@Slf4j
public class OperationParserService {

    private final Map<String, SpecificationParser> parsers = new HashMap<>();
    private final OperationRepository operationRepository;
    private final SystemModelRepository systemModelRepository;
    private final SpecificationGroupRepository specificationGroupRepository;
    private final SpecificationSourceRepository specificationSourceRepository;
    private final ActionsLogService actionLogger;
    private final TransactionHandler transactionHandler;

    @Autowired
    public OperationParserService(List<SpecificationParser> parsers,
                                  OperationRepository operationRepository,
                                  SystemModelRepository systemModelRepository,
                                  SpecificationGroupRepository specificationGroupRepository,
                                  SpecificationSourceRepository specificationSourceRepository,
                                  ActionsLogService actionLogger,
                                  TransactionHandler transactionHandler) {
        this.operationRepository = operationRepository;
        this.systemModelRepository = systemModelRepository;
        this.specificationGroupRepository = specificationGroupRepository;
        this.specificationSourceRepository = specificationSourceRepository;
        this.actionLogger = actionLogger;
        this.transactionHandler = transactionHandler;
        for (SpecificationParser parser : parsers) {
            Parser parserAnnotation = parser.getClass().getAnnotation(Parser.class);
            if (parserAnnotation != null) {
                this.parsers.put(parserAnnotation.value(), parser);
            }
        }
    }

    private SpecificationParser getParser(String parserName) {
        return this.parsers.get(parserName);
    }

    public CompletableFuture<SystemModel> parse(String parserName,
                                                String specificationGroupId,
                                                Collection<SpecificationSource> specificationSources,
                                                boolean isDiscovered,
                                                Set<String> oldSystemModelsIds,
                                                Consumer<String> messageHandler) {
        String requestId = RequestIdContext.get();
        return CompletableFuture.supplyAsync(() -> {
            RequestIdContext.set(requestId);
            return transactionHandler.supplyInNewTransaction(() -> {
                SpecificationGroup specificationGroup = specificationGroupRepository.getReferenceById(specificationGroupId);
                SpecificationParser parser = getParser(parserName);

                SystemModel systemModel = parser.enrichSpecificationGroup(specificationGroup, specificationSources, oldSystemModelsIds, isDiscovered, messageHandler);
                systemModel.setSource(SystemModelSource.MANUAL);

                List<SpecificationSource> specSources = specificationSourceRepository.saveAll(specificationSources);
                specSources.forEach(systemModel::addProvidedSpecificationSource);

                systemModel = systemModelRepository.save(systemModel);
                operationRepository.saveAll(systemModel.getOperations());
                specificationSourceRepository.saveAll(specSources);

                logSystemModelAction(systemModel, specificationGroup, LogOperation.CREATE);
                return systemModel;
            });
        });
    }

    private void logSystemModelAction(AbstractSystemEntity object, SpecificationGroup parent, LogOperation logOperation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SPECIFICATION)
                .entityId(object.getId())
                .entityName(object.getName())
                .parentId(parent == null ? null : parent.getId())
                .parentName(parent == null ? null : parent.getName())
                .parentType(parent == null ? null : EntityType.SPECIFICATION_GROUP)
                .operation(logOperation)
                .build());
    }

}
