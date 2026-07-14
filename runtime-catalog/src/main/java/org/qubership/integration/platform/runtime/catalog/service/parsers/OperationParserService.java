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
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.runtime.catalog.context.RequestIdContext;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelBaseService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OperationParserService {

    private final Map<String, SpecificationParser> parsers = new HashMap<>();
    private final OperationRepository operationRepository;
    private final SystemModelRepository systemModelRepository;
    private final SpecificationGroupRepository specificationGroupRepository;
    private final SpecificationSourceRepository specificationSourceRepository;
    private final SystemModelBaseService systemModelBaseService;
    private final ActionsLogService actionLogger;
    private final TransactionHandler transactionHandler;

    @Autowired
    public OperationParserService(List<SpecificationParser> parsers,
                                  OperationRepository operationRepository,
                                  SystemModelRepository systemModelRepository,
                                  SpecificationGroupRepository specificationGroupRepository,
                                  SpecificationSourceRepository specificationSourceRepository,
                                  SystemModelBaseService systemModelBaseService,
                                  ActionsLogService actionLogger,
                                  TransactionHandler transactionHandler) {
        this.operationRepository = operationRepository;
        this.systemModelRepository = systemModelRepository;
        this.specificationGroupRepository = specificationGroupRepository;
        this.specificationSourceRepository = specificationSourceRepository;
        this.systemModelBaseService = systemModelBaseService;
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

                ParsedSystemModel parsedSystemModel =
                        parser.parseSpecification(specificationGroup, specificationSources, messageHandler);

                SystemModel systemModel = buildSystemModel(
                        parsedSystemModel, specificationGroup, oldSystemModelsIds, messageHandler);

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

    /**
     * Turns a parsed model into a persistable system model attached to its group. Resolves the
     * version name, rejects a duplicate version or id, maps the parsed operations onto entities, and
     * assigns their ids. The version name doubles as the model name.
     */
    private SystemModel buildSystemModel(ParsedSystemModel parsedSystemModel,
                                         SpecificationGroup specificationGroup,
                                         Set<String> oldSystemModelsIds,
                                         Consumer<String> messageHandler) {
        String groupId = specificationGroup.getId();
        String declaredVersion = parsedSystemModel.getVersion();
        String version = declaredVersion != null ? declaredVersion : generateVersion(groupId);
        checkSimilarVersions(groupId, version);

        String systemModelId = buildId(groupId, version);
        checkSpecId(oldSystemModelsIds, systemModelId);

        SystemModel systemModel = SystemModel.builder().id(systemModelId).build();
        systemModel.setName(version);
        systemModel.setVersion(version);
        systemModel.setDescription(parsedSystemModel.getDescription());
        systemModel.setSource(SystemModelSource.MANUAL);

        List<Operation> operations = parsedSystemModel.getOperations().stream()
                .map(SystemEntitySeam::toPersistenceOperation)
                .collect(Collectors.toList());
        setOperationIds(systemModelId, operations, messageHandler.andThen(log::warn));

        operations.forEach(systemModel::addProvidedOperation);
        specificationGroup.addSystemModel(systemModel);

        return systemModel;
    }

    private String generateVersion(String specificationGroupId) {
        int count = systemModelBaseService.getSystemModelsBySpecificationGroupId(specificationGroupId).size() + 1;
        return count + ".0.0";
    }

    private void checkSimilarVersions(String specificationGroupId, String version) {
        long count = systemModelBaseService.countBySpecificationGroupIdAndVersion(specificationGroupId, version);
        if (count > 0) {
            throw new SpecificationSimilarVersionException(version);
        }
    }

    private String buildId(String parentId, String entityName) {
        return parentId + SpecificationParser.ID_SEPARATOR + entityName;
    }

    private void checkSpecId(Set<String> oldSystemModelsIds, String systemModelId) {
        // skip spec if one already exists (by id) in a system
        if (oldSystemModelsIds.contains(systemModelId)) {
            throw new SpecificationSimilarIdException(systemModelId);
        }
    }

    private String buildOperationId(String systemModelId, String operationName) {
        String operationId = systemModelId + SpecificationParser.ID_SEPARATOR + operationName;
        return operationId.replaceAll("[\\[\\]]", "");
    }

    private void setOperationIds(String systemModelId, Collection<Operation> operations, Consumer<String> messageHandler) {
        Set<String> ids = new HashSet<>();
        for (Operation operation : operations) {
            String idPrefix = buildOperationId(systemModelId, operation.getName());
            String id = idPrefix;
            int index = 0;
            while (ids.contains(id)) {
                if (index == 0) {
                    String message = String.format("Duplicated operation identifier: %s. ", operation.getName());
                    messageHandler.accept(message);
                }
                ++index;
                id = idPrefix + "-" + index;
            }
            operation.setId(id);
            ids.add(id);
        }
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
