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
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.ExtractedSchemas;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.OperationKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
@Slf4j
public class OperationParserService {

    // Import produces structural operations only; schemas are rebuilt on demand by OperationSchemaExtractor.
    private static final boolean IMPORT_WITH_SCHEMAS = false;

    private final Map<String, SpecificationParser> parsers = new HashMap<>();
    private final OperationRepository operationRepository;
    private final SystemModelRepository systemModelRepository;
    private final ApiGroupRepository apiGroupRepository;
    private final SpecificationSourceRepository specificationSourceRepository;
    private final ActionsLogService actionLogger;
    private final TransactionHandler transactionHandler;
    private final OperationSchemaExtractor schemaExtractor;

    @Autowired
    public OperationParserService(List<SpecificationParser> parsers,
                                  OperationRepository operationRepository,
                                  SystemModelRepository systemModelRepository,
                                  ApiGroupRepository apiGroupRepository,
                                  SpecificationSourceRepository specificationSourceRepository,
                                  ActionsLogService actionLogger,
                                  TransactionHandler transactionHandler,
                                  OperationSchemaExtractor schemaExtractor) {
        this.operationRepository = operationRepository;
        this.systemModelRepository = systemModelRepository;
        this.apiGroupRepository = apiGroupRepository;
        this.specificationSourceRepository = specificationSourceRepository;
        this.actionLogger = actionLogger;
        this.transactionHandler = transactionHandler;
        this.schemaExtractor = schemaExtractor;
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
                                                String specificationType,
                                                String specificationVersion,
                                                Consumer<String> messageHandler) {
        String requestId = RequestIdContext.get();
        return CompletableFuture.supplyAsync(() -> {
            RequestIdContext.set(requestId);
            return transactionHandler.supplyInNewTransaction(() -> {
                ApiGroup specificationGroup = apiGroupRepository.getReferenceById(specificationGroupId);
                SpecificationParser parser = getParser(parserName);

                SystemModel systemModel = parser.enrichSpecificationGroup(specificationGroup, specificationSources, oldSystemModelsIds, isDiscovered, IMPORT_WITH_SCHEMAS, messageHandler);
                systemModel.setSource(SystemModelSource.MANUAL);
                systemModel.setSpecificationType(specificationType);
                systemModel.setSpecificationVersion(specificationVersion);

                List<SpecificationSource> specSources = specificationSourceRepository.saveAll(specificationSources);
                specSources.forEach(systemModel::addProvidedSpecificationSource);

                systemModel = systemModelRepository.save(systemModel);
                operationRepository.saveAll(systemModel.getOperations());
                specificationSourceRepository.saveAll(specSources);

                logSystemModelAction(systemModel, specificationGroup, LogOperation.CREATE);
                warnWhenSchemasCannotBeRebuilt(systemModel, protocolOf(specificationGroup), specSources, messageHandler);
                return systemModel;
            });
        });
    }

    // A model always hangs off a system in production; a null anywhere reads as "no protocol" and
    // skips the validation rather than failing the import.
    private static OperationProtocol protocolOf(ApiGroup specificationGroup) {
        return specificationGroup == null || specificationGroup.getSystem() == null
                ? null
                : specificationGroup.getSystem().getProtocol();
    }

    /**
     * Import stores structural operations only; schemas are rebuilt on read. A source whose schemas
     * cannot be rebuilt therefore no longer fails here — it degrades to empty schemas at first read,
     * far from the user who imported it. This pass runs the same extraction the read path will run
     * and warns while the import is still on screen. Never a failure: the operations imported fine.
     */
    private void warnWhenSchemasCannotBeRebuilt(SystemModel model,
                                                OperationProtocol protocol,
                                                List<SpecificationSource> sources,
                                                Consumer<String> messageHandler) {
        if (!OperationSchemaExtractor.canExtractSchemas(protocol)) {
            return;
        }
        try {
            Map<OperationKey, ExtractedSchemas> extracted = schemaExtractor.extractAll(sources, protocol, true);
            List<OperationKey> unmatched = model.getOperations().stream()
                    .map(operation -> OperationKey.of(operation.getPath(), operation.getMethod()))
                    .filter(key -> !extracted.containsKey(key))
                    .toList();
            if (!unmatched.isEmpty()) {
                messageHandler.accept("Request and response schemas of "
                        + OperationSchemaExtractor.describeKeys(unmatched)
                        + " cannot be rebuilt from the imported source and will read as empty. ");
            }
        } catch (Exception exception) {
            log.warn("Cannot rebuild operation schemas of imported specification {}", model.getId(), exception);
            messageHandler.accept("Operation schemas cannot be rebuilt from the imported source ("
                    + exception.getMessage()
                    + "), so they will read as empty. The operations themselves were imported. ");
        }
    }

    private void logSystemModelAction(AbstractSystemEntity object, ApiGroup parent, LogOperation logOperation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SPECIFICATION)
                .entityId(object.getId())
                .entityName(object.getName())
                .parentId(parent == null ? null : parent.getId())
                .parentName(parent == null ? null : parent.getName())
                .parentType(parent == null ? null : EntityType.API_GROUP)
                .operation(logOperation)
                .build());
    }

}
