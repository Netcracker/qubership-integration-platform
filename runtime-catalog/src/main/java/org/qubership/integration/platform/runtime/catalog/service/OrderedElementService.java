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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
            
            // Check if there's an element with the exact newPriority
            boolean hasExactPriorityMatch = sortedElements.stream()
                    .anyMatch(it -> orderedElementUtils.getPriorityAsInt(it) == newPriority);
            
            // Find the target index where the element with newPriority would be placed
            int targetIndex = -1;
            for (int i = 0; i < sortedElements.size(); i++) {
                Integer priority = orderedElementUtils.getPriorityAsInt(sortedElements.get(i));
                if (priority.equals(newPriority)) {
                    targetIndex = i;
                    break;
                }
            }
            
            // If no element has the exact newPriority, find the insertion point
            if (targetIndex == -1) {
                // Find the first element with priority > newPriority
                for (int i = 0; i < sortedElements.size(); i++) {
                    Integer priority = orderedElementUtils.getPriorityAsInt(sortedElements.get(i));
                    if (priority > newPriority) {
                        targetIndex = i;
                        break;
                    }
                }
                // If all priorities are less than or equal to newPriority, place at the end
                if (targetIndex == -1) {
                    targetIndex = sortedElements.size() - 1;
                }
            }
            
            orderedElementUtils.updatePriority(element, newPriority);

            // Only shift other elements if we're moving to a position that already has an element with that priority
            if (hasExactPriorityMatch) {
                List<ChainElement> elementsToUpdate;
                if (targetIndex > currentPriorityIndex) {
                    // Moving down in the list (to a higher priority value)
                    // Need to decrement priorities of elements in the range (currentPriorityIndex, targetIndex]
                    elementsToUpdate = IntStream.rangeClosed(currentPriorityIndex + 1, targetIndex)
                            .mapToObj(sortedElements::get)
                            .collect(Collectors.toList());
                } else if (targetIndex < currentPriorityIndex) {
                    // Moving up in the list (to a lower priority value)
                    // Need to increment priorities of elements in the range [targetIndex, currentPriorityIndex)
                    elementsToUpdate = IntStream.range(targetIndex, currentPriorityIndex)
                            .mapToObj(sortedElements::get)
                            .collect(Collectors.toList());
                } else {
                    // Same position, no updates needed
                    elementsToUpdate = List.of();
                }

                for (ChainElement elementToUpdate : elementsToUpdate) {
                    Integer priority = orderedElementUtils.getPriorityAsInt(elementToUpdate);
                    if (targetIndex > currentPriorityIndex) {
                        // Moving down, decrement priorities
                        orderedElementUtils.updatePriority(elementToUpdate, priority - 1);
                    } else {
                        // Moving up, increment priorities
                        orderedElementUtils.updatePriority(elementToUpdate, priority + 1);
                    }
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
