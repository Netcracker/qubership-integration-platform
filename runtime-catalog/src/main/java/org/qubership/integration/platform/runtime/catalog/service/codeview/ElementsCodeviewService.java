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

package org.qubership.integration.platform.runtime.catalog.service.codeview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementsCodeException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class ElementsCodeviewService {
    private final ChainFinderService chainFinderService;
    private final YAMLMapper yamlMapper;
    private final ActionsLogService actionLogger;
    private final AuditingHandler auditingHandler;

    @Autowired
    public ElementsCodeviewService(ChainFinderService chainFinderService,
                                   YAMLMapper codeViewYamlMapper, ActionsLogService actionLogger, AuditingHandler auditingHandler) {
        this.chainFinderService = chainFinderService;
        this.yamlMapper = codeViewYamlMapper;
        this.actionLogger = actionLogger;
        this.auditingHandler = auditingHandler;
    }

    public String getElementsAsCode(String chainId) {
        try {
            Chain chain = chainFinderService.findById(chainId);
            return yamlMapper.writeValueAsString(chain.getElements());
        } catch (JsonProcessingException e) {
            throw new ElementsCodeException("Failed to save elements from YAML", e);
        }
    }

    @Deprecated(forRemoval = true, since = "24.2")
    @ChainModification
    public List<ChainElement> saveElementsAsCode(String chainId, String elementsYaml) {
        try {
            List<ChainElement> chainElements = yamlMapper.readValue(elementsYaml, new TypeReference<>() {});
            Chain chain = chainFinderService.findById(chainId);

            Map<String, ChainElement> parsedElements = chainElements.stream()
                    .collect(Collectors.toMap(AbstractEntity::getId, Function.identity()));
            mergeElements(parsedElements, chain.getElements());

            auditingHandler.markModified(chain);
            logChainAction(chain, LogOperation.UPDATE);

            return chain.getRootElements();
        } catch (JsonProcessingException e) {
            throw new ElementsCodeException("Failed to save elements from YAML", e);
        }
    }

    @Deprecated(forRemoval = true, since = "24.2")
    private void mergeElements(Map<String, ChainElement> parsedElements, List<ChainElement> originalElements) {
        for (ChainElement originalElement : originalElements) {
            ChainElement updatedElement = parsedElements.get(originalElement.getId());
            if (updatedElement != null) {
                if (updatedElement.getName() != null && !updatedElement.getName().isEmpty()) {
                    originalElement.setName(updatedElement.getName());
                }
                originalElement.setDescription(updatedElement.getDescription());
                if (!(originalElement instanceof ContainerChainElement)) {
                    originalElement.getProperties().putAll(updatedElement.getProperties());
                }
            }
        }
    }

    @Deprecated(forRemoval = true, since = "24.2")
    private void logChainAction(Chain chain, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.CHAIN)
                .entityId(chain.getId())
                .entityName(chain.getName())
                .parentType(chain.getParentFolder() == null ? null : EntityType.FOLDER)
                .parentId(chain.getParentFolder() == null ? null : chain.getParentFolder().getId())
                .parentName(chain.getParentFolder() == null ? null : chain.getParentFolder().getName())
                .operation(operation)
                .build());
    }
}
