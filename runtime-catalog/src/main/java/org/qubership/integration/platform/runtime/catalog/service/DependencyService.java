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

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DependencyValidationException;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.Quantity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.DependencyRepository;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.DistinctByKey;
import org.qubership.integration.platform.runtime.catalog.util.OldContainerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.BiPredicate;

import static org.qubership.integration.platform.runtime.catalog.service.ElementService.CONTAINER_TYPE_NAME;

@Slf4j
@Service
@Transactional
public class DependencyService {

    private static final String DEPENDENCY_WITH_ID_NOT_FOUND_MESSAGE = "Can't find dependency with id: ";
    private final DependencyRepository dependencyRepository;
    private final ElementService elementService;
    private final LibraryElementsService libraryService;
    private final OldContainerUtils oldContainerUtils;

    @Autowired
    public DependencyService(
            DependencyRepository dependencyRepository,
            ElementService elementService,
            LibraryElementsService libraryService,
            OldContainerUtils oldContainerUtils
    ) {
        this.dependencyRepository = dependencyRepository;
        this.elementService = elementService;
        this.libraryService = libraryService;
        this.oldContainerUtils = oldContainerUtils;
    }

    public Dependency findById(String dependencyId) {
        return dependencyRepository.findById(dependencyId)
                .orElseThrow(() -> new EntityNotFoundException(DEPENDENCY_WITH_ID_NOT_FOUND_MESSAGE + dependencyId));
    }

    public List<Dependency> findAllByElementsIDs(List<String> elementIDs) {
        return dependencyRepository.findByElementIDs(elementIDs);
    }

    @ChainModification
    public ChainDiff create(String from, String to) {
        ChainElement elementFrom = elementService.findById(from);
        ChainElement elementTo = elementService.findById(to);
        return create(elementFrom, elementTo);
    }

    @ChainModification
    public ChainDiff create(@NonNull ChainElement elementFrom, @NonNull ChainElement elementTo) {
        final ChainDiff chainDiff = new ChainDiff();

        Dependency dependency = new Dependency();

        validateDependency(elementFrom, elementTo);

        Optional<Dependency> alreadyCreatedOptional = dependencyRepository
                .findByFromAndTo(elementFrom.getId(), elementTo.getId());
        if (alreadyCreatedOptional.isEmpty()) {
            final ContainerChainElement elementFromParent = oldContainerUtils
                    .getOldContainerParent(findFirstNonGroupParent(elementFrom));
            if (elementFromParent != null) {
                validateElementToDependencies(elementFromParent, elementFrom, elementTo);

                List<ChainElement> changedElements = new ArrayList<>();
                collectAllDependentRootElements(changedElements, elementFromParent.getId(), elementTo);
                changedElements.stream()
                        .filter(DistinctByKey.newInstance(ChainElement::getId))
                        .map(element -> {
                            elementService.checkIfAllowedInContainers(element.getType());

                            element.setParent(elementFromParent);
                            element.setSwimlane(elementFrom.getSwimlane());
                            return elementService.save(element);
                        })
                        .forEach(chainDiff::addUpdatedElement);
            }

            dependency.setElementFrom(elementFrom);
            dependency.setElementTo(elementTo);
            dependency = dependencyRepository.save(dependency);
            elementFrom.addOutputDependency(dependency);
            elementTo.addInputDependency(dependency);
            chainDiff.addCreatedDependency(dependency);
            return chainDiff;
        } else {
            throw new EntityExistsException(
                    "Dependency from " + elementFrom.getId() + " to " + elementTo.getId() + " already exists"
            );
        }
    }

    @ChainModification
    public ChainDiff deleteById(String id) {
        final ChainDiff chainDiff = new ChainDiff();

        Dependency dependency = dependencyRepository.getReferenceById(id);

        dependencyRepository.deleteById(id);
        chainDiff.addRemovedDependency(dependency);

        return chainDiff;
    }

    @ChainModification
    public ChainDiff deleteAllByIds(List<String> ids) {
        final ChainDiff chainDiff = new ChainDiff();

        List<Dependency> dependencies = dependencyRepository.findAllById(ids);

        dependencyRepository.deleteAllById(ids);
        chainDiff.addRemovedDependencies(dependencies);

        return chainDiff;
    }

    private void validateDependency(ChainElement elementFrom, ChainElement elementTo) {
        if (CONTAINER_TYPE_NAME.equals(elementFrom.getType()) || CONTAINER_TYPE_NAME.equals(elementTo.getType())) {
            throw new DependencyValidationException("Dependency from/to container could not be created");
        }

        if (SwimlaneService.SWIMLANE_TYPE_NAME.equals(elementFrom.getType()) || SwimlaneService.SWIMLANE_TYPE_NAME.equals(elementTo.getType())) {
            throw new DependencyValidationException("Dependency from/to swimlane could not be created");
        }

        if (elementTo.getParent() != null) {
            ContainerChainElement elementFromParent = elementFrom.getParent();
            ContainerChainElement elementToParent = elementTo.getParent();
            BiPredicate<ContainerChainElement, ContainerChainElement> sameParents = (fromParent, toParent) ->
                    StringUtils.equals(fromParent.getId(), toParent.getId())
                    || (oldContainerUtils.isOldStyleContainer(fromParent.getType())
                        && (Objects.equals(toParent, fromParent.getParent())));
            if (!CONTAINER_TYPE_NAME.equals(elementToParent.getType())
                && (elementFromParent == null || !sameParents.test(elementFromParent, elementToParent))) {
                throw new DependencyValidationException("Dependency to container child cannot be created");
            }

            if (!CONTAINER_TYPE_NAME.equals(elementToParent.getType())) {
                long startElementsCount = elementToParent.getElements().stream()
                        .filter(element -> element.getInputDependencies().isEmpty())
                        .filter(element -> !Objects.equals(element, elementTo))
                        .count();
                if (startElementsCount < 1) {
                    throw new DependencyValidationException("Dependency to the only one start element cannot be created");
                }
            }
        }

        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(elementTo);
        if (elementDescriptor == null) {
            throw new DependencyValidationException("Element of type " + elementTo.getType() + " not found");
        }

        if (!elementDescriptor.isInputEnabled()) {
            throw new DependencyValidationException("Input dependency disabled for " + elementTo.getType());
        }

        int inputDepCount = elementTo.getInputDependencies().size() + 1;
        Quantity inputQuantity = elementDescriptor.getInputQuantity();
        if (!inputQuantity.test(inputDepCount)) {
            throw new DependencyValidationException(
                    String.format("Only %s input dependencies available for %s", inputQuantity.name(), elementTo.getType())
            );
        }
    }

    private void validateElementToDependencies(ContainerChainElement fromParent, ChainElement elementFrom, ChainElement elementTo) {
        if (Objects.equals(elementFrom, elementTo) || Objects.equals(fromParent, elementTo.getParent())) {
            return;
        }

        if (fromParent != null && !CONTAINER_TYPE_NAME.equals(fromParent.getType())
            && elementTo.getInputDependencies().stream()
                    .anyMatch(dependency -> !Objects.equals(elementFrom, dependency.getElementFrom()))) {
            throw new DependencyValidationException("Element "
                                                    + elementTo.getId() + " already has input dependencies with a different parent");
        }

        ContainerChainElement elementFromParent = fromParent;
        do {
            if (Objects.equals(elementTo, elementFromParent)) {
                throw new DependencyValidationException("Dependency to parent cannot be created");
            }
        } while ((elementFromParent = elementFromParent.getParent()) != null);

        elementTo.getOutputDependencies()
                .forEach(dependency -> validateElementToDependencies(
                        fromParent, dependency.getElementFrom(), dependency.getElementTo()
                ));
    }

    private void collectAllDependentRootElements(
            List<ChainElement> elementsToChange,
            String elementFromParentId,
            ChainElement elementTo
    ) {
        Queue<ChainElement> elementsTo = new LinkedList<>();
        elementsTo.offer(elementTo);

        while (!elementsTo.isEmpty()) {
            ChainElement nextElement = elementsTo.poll();
            ContainerChainElement nextElementParent = nextElement.getParent();
            if (nextElementParent != null && !StringUtils.equals(elementFromParentId, nextElementParent.getId())) {
                throw new DependencyValidationException("Unable to create a dependency for elements with different parents");
            }
            elementsToChange.add(nextElement);

            List<Dependency> outputDependencies = new ArrayList<>(nextElement.getOutputDependencies());
            outputDependencies.addAll(oldContainerUtils.extractOldContainerOutputDependencies(nextElement));
            outputDependencies.stream()
                    .map(Dependency::getElementTo)
                    .filter(outputElement -> !Objects.equals(outputElement, elementTo))
                    .forEach(elementsTo::offer);
        }
    }

    @Nullable
    private ContainerChainElement findFirstNonGroupParent(ChainElement element) {
        ContainerChainElement parent = element.getParent();
        while (parent != null && CONTAINER_TYPE_NAME.equals(parent.getType())) {
            parent = parent.getParent();
        }
        return parent;
    }

    public void setActualizedElementDependencyStates(Set<Dependency> oldDependencyStates, Set<Dependency> newDependencyStates) {
        dependencyRepository.actualizeCollectionState(oldDependencyStates, newDependencyStates);
    }
}
