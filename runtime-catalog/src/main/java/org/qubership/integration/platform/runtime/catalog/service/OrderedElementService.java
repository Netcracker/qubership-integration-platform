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

import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.OrderedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Service
@Transactional
public class OrderedElementService {

    private final LibraryElementsService libraryService;


    public OrderedElementService(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }


    public void calculatePriority(@NonNull ContainerChainElement parentElement, ChainElement element) {
        OrderedElementUtils orderedElementUtils = new OrderedElementUtils(libraryService.getElementDescriptor(element), element);
        final List<ChainElement> orderedElements = orderedElementUtils.extractOrderedElements(parentElement, true);
        Integer orderNumber = (int) orderedElements.stream()
                .filter(it -> {
                    int currentOrderNumber = orderedElementUtils.getPriorityAsInt(it);
                    return currentOrderNumber >= 0 && currentOrderNumber < orderedElements.size();
                })
                .count();
        orderedElementUtils.updatePriority(element, orderNumber);
    }

    public ChainDiff changePriority(@NonNull ContainerChainElement parentElement, ChainElement element, Integer newPriority) {
        if (newPriority < 0) {
            throw new IllegalArgumentException("Priority cannot be a negative number");
        }

        final ChainDiff chainDiff = new ChainDiff();

        OrderedElementUtils orderedElementUtils = new OrderedElementUtils(libraryService.getElementDescriptor(element), element);
        Integer currentPriority = orderedElementUtils.getPriorityAsInt(element);

        if (!currentPriority.equals(newPriority)) {
            List<ChainElement> sortedElements = orderedElementUtils.getSortedChildren(parentElement);
            int currentPriorityIndex = orderedElementUtils.getCurrentElementIndex(sortedElements);
            int newPriorityIndex = orderedElementUtils.getIndexByPriority(sortedElements, newPriority);
            orderedElementUtils.updatePriority(element, newPriority);

            List<ChainElement> elementsToUpdate;
            Function<Integer, Integer> priorityFunction;
            if (newPriority > currentPriority) {
                if (newPriorityIndex == -1) {
                    if (currentPriorityIndex + 1 >= sortedElements.size()) {
                        return chainDiff;
                    }
                    newPriorityIndex = sortedElements.stream()
                            .map(orderedElementUtils::getPriorityAsInt)
                            .filter(it -> it < sortedElements.size())
                            .max(Integer::compareTo)
                            .orElse(currentPriorityIndex);
                }
                elementsToUpdate = sortedElements.subList(currentPriorityIndex + 1, newPriorityIndex + 1);
                priorityFunction = (priority) -> priority - 1;
            } else {
                if (newPriorityIndex == -1) {
                    return chainDiff;
                }
                elementsToUpdate = sortedElements.subList(newPriorityIndex, currentPriorityIndex);
                priorityFunction = (priority) -> priority + 1;
            }

            for (ChainElement elementToUpdate : elementsToUpdate) {
                Integer priority = orderedElementUtils.getPriorityAsInt(elementToUpdate);
                if (priority < sortedElements.size()) {
                    orderedElementUtils.updatePriority(elementToUpdate, priorityFunction.apply(priority));
                    chainDiff.addUpdatedElement(elementToUpdate);
                }
            }
        }

        return chainDiff;
    }

    public ChainDiff removeOrderedElement(@NonNull ContainerChainElement parentElement, ChainElement element) {
        final ChainDiff chainDiff = new ChainDiff();

        OrderedElementUtils orderedElementUtils = new OrderedElementUtils(libraryService.getElementDescriptor(element), element);
        int currentPriority = orderedElementUtils.getPriorityAsInt(element);
        if (currentPriority < parentElement.getElements().size()) {
            List<ChainElement> sortedElements = orderedElementUtils.getSortedChildren(parentElement);
            int currentPriorityIndex = orderedElementUtils.getCurrentElementIndex(sortedElements);
            int lastPriorityIndex = (int) sortedElements.stream()
                    .filter(it -> orderedElementUtils.getPriorityAsInt(it) < sortedElements.size())
                    .count() - 1;
            List<ChainElement> elementsToUpdate = sortedElements.subList(currentPriorityIndex + 1, lastPriorityIndex + 1);
            for (ChainElement elementToUpdate : elementsToUpdate) {
                Integer priority = orderedElementUtils.getPriorityAsInt(elementToUpdate);
                orderedElementUtils.updatePriority(elementToUpdate, priority - 1);
                chainDiff.addUpdatedElement(elementToUpdate);
            }
        }

        return chainDiff;
    }

    public boolean isOrdered(@NonNull ChainElement element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element);
        return descriptor != null && descriptor.isOrdered() && element.getParent() != null;
    }

    public Optional<Integer> extractPriorityNumber(String elementType, Map<String, Object> properties) {
        Optional<Integer> priorityNumber = Optional.empty();
        ElementDescriptor descriptor = libraryService.getElementDescriptor(elementType);
        if (descriptor != null) {
            priorityNumber = Optional.ofNullable(properties.get(descriptor.getPriorityProperty()))
                    .map(OrderedElementUtils::convertPriorityToInt);
        }

        return priorityNumber;
    }
}
