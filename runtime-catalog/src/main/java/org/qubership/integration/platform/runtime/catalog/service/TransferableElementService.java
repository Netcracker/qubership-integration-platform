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

import org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementCreationException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementTransferException;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.CreateElementRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.TransferElementRequest;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.util.OldContainerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


@Service
@Transactional
public class TransferableElementService extends ElementService {

    protected final OldContainerUtils oldContainerUtils;
    private final DependencyService dependencyService;

    @Autowired
    public TransferableElementService(
            ElementRepository elementRepository,
            LibraryElementsService libraryService,
            ChainFinderService chainFinderService,
            SwimlaneService swimlaneService,
            ActionsLogService actionLogger,
            AuditingHandler jpaAuditingHandler,
            OrderedElementService orderedElementService,
            ElementUtils elementUtils,
            OldContainerUtils oldContainerUtils,
            DependencyService dependencyService,
            SystemEnvironmentsGenerator systemEnvironmentsGenerator
    ) {
        super(
                elementRepository,
                jpaAuditingHandler,
                libraryService,
                chainFinderService,
                swimlaneService,
                actionLogger,
                orderedElementService,
                elementUtils,
                systemEnvironmentsGenerator
        );
        this.oldContainerUtils = oldContainerUtils;
        this.dependencyService = dependencyService;
    }

    @Override
    @ChainModification
    public ChainDiff create(String chainId, CreateElementRequest createElementRequest) {
        final ChainDiff chainDiff = new ChainDiff();

        String parentElementId = createElementRequest.getParentElementId();
        String elementType = createElementRequest.getType();
        ChainElement newElement;
        if (SwimlaneService.SWIMLANE_TYPE_NAME.equals(elementType)) {
            return swimlaneService.create(chainId);
        } else if (parentElementId != null) {
            ChainElement parentElement = findByIdAndChainId(parentElementId, chainId)
                    .orElseThrow(() -> new ElementCreationException(
                            "Element " + parentElementId + " does not exist in chain " + chainId));
            boolean restrictedParentContainer = isParentContainerRestricted(parentElement, Collections.singletonList(elementType));
            if (!(parentElement instanceof ContainerChainElement) || restrictedParentContainer) {
                String swimlaneId = Optional.ofNullable(parentElement.getSwimlane())
                        .map(ChainElement::getId)
                        .orElse(null);
                createElementRequest.setSwimlaneId(swimlaneId);
                newElement = create(chainDiff, chainId, createElementRequest);
                chainDiff.addCreatedElement(newElement);
                chainDiff.merge(dependencyService.create(parentElement, newElement));
                return chainDiff;
            }
            newElement = create(elementType, (ContainerChainElement) parentElement);
            chainDiff.addUpdatedElement(parentElement);
        } else {
            newElement = create(chainDiff, chainId, createElementRequest);
        }
        chainDiff.addCreatedElement(newElement);
        return chainDiff;
    }

    @ChainModification
    public ChainDiff transfer(String chainId, TransferElementRequest transferRequest) {
        String parentId = transferRequest.getParentId();
        String swimlaneId = transferRequest.getSwimlaneId();
        List<String> elementIds = transferRequest.getElements();
        List<ChainElement> elements = new ArrayList<>();
        // required to perform validation on all children of old style container.
        findAllById(elementIds).forEach(element -> {
            elements.add(element);
            elements.addAll(oldContainerUtils.getAllOldStyleContainerChildren(element));
        });
        final ChainDiff chainDiff = new ChainDiff();

        ContainerChainElement parentElement = null;
        if (parentId != null) {
            elements.forEach(element -> checkIfAllowedInContainers(element.getType()));

            ChainElement foundParentElement = findByIdOptional(parentId)
                    .orElseThrow(() -> new ElementTransferException("Element with id " + parentId + " not found"));
            boolean restrictedParentContainer = isParentContainerRestricted(
                    foundParentElement,
                    elements.stream().map(ChainElement::getType).toList()
            );
            if (!(foundParentElement instanceof ContainerChainElement) || restrictedParentContainer) {
                return createDependencies(foundParentElement, elementIds);
            }
            parentElement = (ContainerChainElement) foundParentElement;
            swimlaneId = Optional.ofNullable(parentElement.getSwimlane())
                    .map(ChainElement::getId)
                    .orElse(swimlaneId);
        }

        List<String> foundElementIds = elements.stream()
                .map(ChainElement::getId)
                .toList();
        validateIntoItselfMovement(parentElement, foundElementIds);
        // contains IDs of the parents from which the elements were transferred
        Set<String> oldParentIds = new HashSet<>();
        for (ChainElement element : elements) {
            String parentElementType = Optional.ofNullable(parentElement)
                    .map(ChainElement::getType)
                    .orElse(null);
            checkElementParentRestriction(element.getType(), parentElementType);
            if (parentElement != null) {
                if (element.getInputDependencies().isEmpty()) {
                    checkAddingChildParentRestriction(element.getType(), parentElement);
                }
                parentElement.getElements().add(element);
                if (orderedElementService.isOrdered(element)) {
                    ChainDiff orderedChainDiff = orderedElementService.removeOrderedElement(element.getParent(), element);
                    saveAll(orderedChainDiff.getUpdatedElements());
                    chainDiff.merge(orderedChainDiff);
                    orderedElementService.calculatePriority(parentElement, element);
                }
            }
            ChainElement parentWithDeletedChild = deleteElementFromParent(element, false);
            if (parentWithDeletedChild != null) {
                chainDiff.addUpdatedElement(parentWithDeletedChild);
                oldParentIds.add(parentWithDeletedChild.getId());
            } else {
                chainDiff.addUpdatedElement(element);
            }
            validateTransferElementDependencies(element, foundElementIds);

            element.setParent(parentElement);
        }
        List<ChainElement> savedElements = saveAll(elements);
        if (swimlaneId != null) {
            chainDiff.merge(swimlaneService.transferElementsToSwimlane(chainId, swimlaneId, elements));
        }
        if (parentElement != null) {
            if (isParentsNotInSet(parentElement, oldParentIds)) {
                chainDiff.addUpdatedElement(parentElement);
            }
        } else {
            chainDiff.addUpdatedElements(savedElements);
        }

        return chainDiff;
    }

    private boolean isParentContainerRestricted(ChainElement parentElement, List<String> elementTypes) {
        return Optional.ofNullable(libraryService.getElementDescriptor(parentElement))
                .filter(ElementDescriptor::isContainer)
                .map(ElementDescriptor::getAllowedChildren)
                .map(allowedChildren ->
                        !allowedChildren.isEmpty() && elementTypes.stream()
                                .anyMatch(elementType -> !allowedChildren.containsKey(elementType)))
                .orElse(false);
    }

    private ChainDiff createDependencies(ChainElement elementFrom, List<String> elementToIds) {
        validateIntoItselfMovement(elementFrom, elementToIds);

        final ChainDiff chainDiff = new ChainDiff();
        findAllById(elementToIds).stream()
                .peek(it -> validateTransferElementDependencies(it, elementToIds))
                .filter(it -> it.getInputDependencies().isEmpty())
                .peek(this::validateIfInputEnabled)
                .map(it -> dependencyService.create(elementFrom, it))
                .forEach(chainDiff::merge);
        return chainDiff;
    }

    private void validateIntoItselfMovement(ChainElement parentElement, List<String> elementToIds) {
        while (parentElement != null) {
            if (elementToIds.contains(parentElement.getId())) {
                throw new ElementTransferException("Element cannot be transfer into itself");
            }
            parentElement = parentElement.getParent();
        }
    }

    private void validateIfInputEnabled(ChainElement element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element.getType());
        if (descriptor == null) {
            throw new ElementTransferException("The " + element.getType() + " with id " + element.getId() + " not found");
        }
        if (!descriptor.isInputEnabled()) {
            throw new ElementTransferException("The " + element.getType() + " input disabled");
        }
    }

    private void validateTransferElementDependencies(ChainElement element, List<String> transferElementsIds) {
        long inputsCount = element.getInputDependencies().stream()
                .map(Dependency::getElementFrom)
                .filter(elementFrom -> !transferElementsIds.contains(elementFrom.getId())
                        && !isElementParentTransferableOldStyleContainer(elementFrom, transferElementsIds))
                .count();
        long outputCounts = element.getOutputDependencies().stream()
                .map(Dependency::getElementTo)
                .filter(elementTo -> !transferElementsIds.contains(elementTo.getId()))
                .count();
        if (inputsCount > 0 || outputCounts > 0) {
            throw new ElementTransferException("Element " + element.getName() + " has input/output dependencies");
        }
    }

    private boolean isElementParentTransferableOldStyleContainer(ChainElement element, List<String> transferElementsIds) {
        if (element.getParent() == null || !oldContainerUtils.isOldStyleContainer(element.getParent().getType())) {
            return false;
        }

        return transferElementsIds.contains(element.getParent().getId());
    }

    private boolean isParentsNotInSet(ChainElement element, Set<String> ids) {
        ChainElement currentElement = element;
        while (currentElement.getParent() != null) {
            if (ids.contains(currentElement.getParent().getId())) {
                return false;
            }
            currentElement = currentElement.getParent();
        }
        return true;
    }
}
