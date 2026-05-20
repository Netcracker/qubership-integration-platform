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

package org.qubership.integration.platform.runtime.catalog.util;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;

import java.util.List;
import java.util.Objects;

public class OrderedElementUtils {

    private final ElementDescriptor elementDescriptor;
    private final ChainElement orderedElement;


    public OrderedElementUtils(ElementDescriptor elementDescriptor, ChainElement orderedElement) {
        this.elementDescriptor = elementDescriptor;
        this.orderedElement = orderedElement;
    }


    public List<ChainElement> extractOrderedElements(ContainerChainElement parentElement, boolean excludeCurrentElement) {
        return parentElement.getElements().stream()
                .filter(it -> StringUtils.equals(it.getType(), orderedElement.getType()))
                .filter(it -> excludeCurrentElement && !StringUtils.equals(it.getId(), orderedElement.getId()))
                .toList();
    }

    public List<ChainElement> getSortedChildren(ContainerChainElement parentElement) {
        return parentElement.getElements().stream()
                .filter(it -> StringUtils.equals(it.getType(), orderedElement.getType()))
                .sorted((left, right) -> {
                    Integer leftPriorityNumber = getPriorityAsInt(left);
                    Integer rightPriorityNumber = getPriorityAsInt(right);

                    if (leftPriorityNumber.equals(rightPriorityNumber)) {
                        return 0;
                    }
                    return leftPriorityNumber > rightPriorityNumber ? 1 : -1;
                })
                .toList();
    }

    public Integer getCurrentElementIndex(List<ChainElement> sortedElements) {
        return sortedElements.stream()
                .filter(it -> StringUtils.equals(it.getId(), orderedElement.getId()))
                .findFirst()
                .map(sortedElements::indexOf)
                .orElse(-1);
    }

    public Integer getIndexByPriority(List<ChainElement> sortedElements, Integer priority) {
        return sortedElements.stream()
                .filter(it -> Objects.equals(getPriorityAsInt(it), priority))
                .findFirst()
                .map(sortedElements::indexOf)
                .orElse(-1);
    }

    public Integer getPriorityAsInt(ChainElement element) {
        Object priority = element.getProperty(elementDescriptor.getPriorityProperty());
        return convertPriorityToInt(priority);
    }

    public void updatePriority(ChainElement element, Integer priority) {
        element.getProperties().put(elementDescriptor.getPriorityProperty(), priority);
    }

    public static Integer convertPriorityToInt(Object priority) {
        try {
            return Integer.parseInt(String.valueOf(priority));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Priority number must be an integer: " + priority, e);
        }
    }
}
