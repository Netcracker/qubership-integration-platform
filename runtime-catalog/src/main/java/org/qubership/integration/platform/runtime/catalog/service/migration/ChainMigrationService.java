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

package org.qubership.integration.platform.runtime.catalog.service.migration;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ChainMigrationException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.DependencyRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.migration.element.ElementMigration;
import org.qubership.integration.platform.runtime.catalog.service.migration.element.MigrationContext;
import org.qubership.integration.platform.runtime.catalog.service.migration.element.RestrictedContainerMigration;
import org.qubership.integration.platform.runtime.catalog.util.DistinctByKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CONTAINER;


@Slf4j
@Service
public class ChainMigrationService {

    private static final String CONTAINING_SWIMLANES_ERROR_MESSAGE = "Chain containing swimlanes cannot be migrated.";
    private static final String CHAIN_WITH_ID_NOT_FOUND_MESSAGE = "Can't find chain with id: ";
    private final ActionsLogService actionLogger;
    private final AuditingHandler auditingHandler;
    private final ChainRepository chainRepository;
    private final ElementRepository elementRepository;
    private final DependencyRepository dependencyRepository;
    private final Map<String, ElementMigration> elementMigrations;

    @Autowired
    public ChainMigrationService(
            ActionsLogService actionLogger,
            AuditingHandler auditingHandler,
            ChainRepository chainRepository,
            ElementRepository elementRepository,
            DependencyRepository dependencyRepository,
            List<ElementMigration> elementMigrations
    ) {
        this.actionLogger = actionLogger;
        this.auditingHandler = auditingHandler;
        this.chainRepository = chainRepository;
        this.elementRepository = elementRepository;
        this.elementMigrations = elementMigrations.stream()
                .collect(Collectors.toMap(ElementMigration::getOldElementType, Function.identity()));
        this.dependencyRepository = dependencyRepository;
    }

    public boolean containsDeprecatedContainers(Chain chain) {
        return chain.getElements().stream()
                .map(ChainElement::getType)
                .anyMatch(elementMigrations::containsKey);
    }

    @Transactional
    @ChainModification
    public MigratedChain migrateChain(String chainId) {
        MigrationContext context = new MigrationContext(elementMigrations);
        Chain chainToMigrate = chainRepository.findById(chainId)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_WITH_ID_NOT_FOUND_MESSAGE + chainId));
        Chain migratedChain = getMigratedChain(chainToMigrate, context);

        migratedChain.getDependencies().forEach(dependencyRepository::persist);
        migratedChain.getElements().forEach(elementRepository::persist);
        chainRepository.saveEntity(migratedChain);

        auditingHandler.markModified(migratedChain);
        logChainAction(migratedChain, LogOperation.UPDATE);

        chainRepository.clearContext();

        migratedChain = chainRepository.findById(chainId)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_WITH_ID_NOT_FOUND_MESSAGE + chainId));

        boolean groupsRemoved = !context.getGroupsToDelete().isEmpty();

        return new MigratedChain(migratedChain, groupsRemoved);
    }


    public Chain getMigratedChain(Chain chain, MigrationContext context) {
        if (chain.getDefaultSwimlane() != null || chain.getReuseSwimlane() != null) {
            throw new ChainMigrationException(CONTAINING_SWIMLANES_ERROR_MESSAGE);
        }

        chain.getDependencies().forEach(dependencyRepository::remove);
        chain.getElements().forEach(elementRepository::remove);

        Map<ChainElement, ElementMigration> startDeprecatedElements = collectStartDeprecatedContainers(chain.getElements());
        if (!canBeMigrated(startDeprecatedElements, context)) {
            throw new ChainMigrationException(getErrorMessage(chain.getId()));
        }

        try {
            for (Map.Entry<ChainElement, ElementMigration> pair : startDeprecatedElements.entrySet()) {
                ChainElement startDeprecatedElement = pair.getKey();
                ElementMigration elementMigration = pair.getValue();

                if (elementMigration.getOldElementType().equals(startDeprecatedElement.getType())) {
                    elementMigration.migrate(startDeprecatedElement, context);
                }
            }

            chain.removeElements(context.getElementsToDelete());

            List<ChainElement> rootElements = chain.getRootElements();
            chain.getElements().clear();
            chain.addElementsHierarchy(rootElements);

            return chain;
        } catch (Exception exception) {
            String errorMessage = getErrorMessage(chain.getId());
            log.error(errorMessage, exception);
            throw new ChainMigrationException(errorMessage, exception);
        }
    }

    private boolean canBeMigrated(Map<ChainElement, ElementMigration> startDeprecatedElements, MigrationContext context) {
        for (Map.Entry<ChainElement, ElementMigration> pair : startDeprecatedElements.entrySet()) {
            ChainElement startDeprecatedElement = pair.getKey();
            ElementMigration elementMigration = pair.getValue();
            if (!elementMigration.canBeMigrated(startDeprecatedElement, context)) {
                return false;
            }
        }
        return true;
    }

    private Map<ChainElement, ElementMigration> collectStartDeprecatedContainers(List<ChainElement> chainElements) {
        List<ChainElement> startElements = chainElements.stream()
                .filter(chainElement -> chainElement.getParent() == null || CONTAINER.equals(chainElement.getParent().getType()))
                .filter(chainElement -> chainElement.getInputDependencies().isEmpty())
                .filter(chainElement -> !CONTAINER.equals(chainElement.getType()))
                .toList();
        Map<ChainElement, ElementMigration> startDeprecatedContainers = toStartDeprecatedContainerStream(startElements)
                .filter(DistinctByKey.newInstance(Pair::getKey))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
        chainElements.stream()
                .filter(element -> elementMigrations.get(element.getType()) instanceof RestrictedContainerMigration)
                .filter(element -> !startDeprecatedContainers.containsKey(element)
                        && (element.getInputDependencies().isEmpty() || hasOnlyCircularDependenciesOnItself(element)))
                .map(element -> Pair.of(element, elementMigrations.get(element.getType())))
                .forEach(elementMigrationPair -> startDeprecatedContainers.put(elementMigrationPair.getKey(), elementMigrationPair.getValue()));
        return startDeprecatedContainers;
    }

    private Stream<Pair<ChainElement, ElementMigration>> toStartDeprecatedContainerStream(List<ChainElement> chainElements) {
        return chainElements.stream()
                .flatMap(startElement -> {
                    ElementMigration elementMigration = elementMigrations.get(startElement.getType());
                    if (elementMigration == null) {
                        List<ChainElement> outputElements = startElement.getOutputDependencies().stream()
                                .map(Dependency::getElementTo)
                                .toList();
                        return toStartDeprecatedContainerStream(outputElements);
                    }
                    return Stream.of(Pair.of(startElement, elementMigration));
                });
    }

    private boolean hasOnlyCircularDependenciesOnItself(ChainElement chainElement) {
        outerLoop:
        for (Dependency inputDependency : chainElement.getInputDependencies()) {
            Queue<ChainElement> inputElements = new LinkedList<>();
            inputElements.offer(inputDependency.getElementFrom());

            while (!inputElements.isEmpty()) {
                ChainElement inputElement = inputElements.poll();
                if (inputElement.getParent() != null && chainElement.getId().equals(inputElement.getParent().getId())) {
                    continue outerLoop;
                }
                inputElement.getInputDependencies().forEach(dependency -> inputElements.offer(dependency.getElementFrom()));
            }

            return false;
        }
        return true;
    }

    private String getErrorMessage(String chainId) {
        return "Chain " + chainId + " cannot be migrated.";
    }

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
