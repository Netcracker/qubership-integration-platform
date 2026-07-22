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

import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.util.OrderedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

@Service
@Transactional
public class OrderedElementService {
    private final LibraryElementsService libraryService;

    public OrderedElementService(
        LibraryElementsService libraryService
    ) {
        this.libraryService = libraryService;
    }


    public void calculatePriority(@NonNull ContainerChainElement parentElement, ChainElement element) {
        OrderedElementUtils orderedElementUtils = new OrderedElementUtils(
            libraryService.getElementDescriptorOrDefault(element.getType()), element);
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

        OrderedElementUtils orderedElementUtils = new OrderedElementUtils(
            libraryService.getElementDescriptorOrDefault(element.getType()), element);
        Integer currentPriority = orderedElementUtils.getPriorityAsInt(element);

        if (!currentPriority.equals(newPriority)) {
            List<ChainElement> sortedElements = orderedElementUtils.getSortedChildren(parentElement);
            int currentPriorityIndex = orderedElementUtils.getCurrentElementIndex(sortedElements);

            boolean hasExactPriorityMatch = sortedElements.stream()
                    .anyMatch(it -> newPriority.equals(orderedElementUtils.getPriorityAsInt(it)));

            int targetIndex = orderedElementUtils.getIndexToInsert(sortedElements, newPriority);

            orderedElementUtils.updatePriority(element, newPriority);

            // Only shift other elements if we're moving to a position that already has an element with that priority
            if (hasExactPriorityMatch) {
                List<ChainElement> elementsToUpdate = List.of();
                if (targetIndex > currentPriorityIndex) {
                    elementsToUpdate = IntStream.rangeClosed(currentPriorityIndex + 1, targetIndex)
                            .mapToObj(sortedElements::get)
                            .toList();
                } else if (targetIndex < currentPriorityIndex) {
                        elementsToUpdate = IntStream.range(targetIndex, currentPriorityIndex)
                        .mapToObj(sortedElements::get)
                        .toList();
                }

                for (ChainElement elementToUpdate : elementsToUpdate) {
                    Integer priority = orderedElementUtils.getPriorityAsInt(elementToUpdate);
                    orderedElementUtils.updatePriority(elementToUpdate,
                            targetIndex > currentPriorityIndex ? priority - 1 : priority + 1);

                    chainDiff.addUpdatedElement(elementToUpdate);
                }
            }
        }

        return chainDiff;
    }

    public ChainDiff removeOrderedElement(@NonNull ContainerChainElement parentElement, ChainElement element) {
        final ChainDiff chainDiff = new ChainDiff();

        OrderedElementUtils orderedElementUtils = new OrderedElementUtils(
            libraryService.getElementDescriptorOrDefault(element.getType()), element);
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
        return libraryService.lookupElementDescriptor(element.getType())
            .map(descriptor -> descriptor.isOrdered() && element.getParent() != null)
            .orElse(false);
    }

    public Optional<Integer> extractPriorityNumber(String elementType, Map<String, Object> properties) {
        return libraryService.lookupElementDescriptor(elementType).flatMap(descriptor ->
            Optional.ofNullable(properties.get(descriptor.getPriorityProperty()))
                .map(OrderedElementUtils::convertPriorityToInt));
    }
}
