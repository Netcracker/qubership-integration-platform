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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.*;


@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity(name = "container_elements")
public class ContainerChainElement extends ChainElement {

    @Builder.Default
    @OrderBy("id")
    @OneToMany(mappedBy = "parent",
            fetch = FetchType.LAZY)
    private List<ChainElement> elements = new LinkedList<>();

    protected ContainerChainElement(ContainerChainElement container) {
        super(container);
        setElements(new LinkedList<>());
    }

    public void addChildElement(ChainElement element) {
        getElements().add(element);
        element.setParent(this);
    }

    public void addChildrenElements(Collection<ChainElement> elements) {
        elements.forEach(element -> element.setParent(this));
        getElements().addAll(elements);
    }

    public void removeChildElement(ChainElement element) {
        getElements().remove(element);
        element.setParent(null);
    }

    @Override
    public ChainElement copy() {
        return new ContainerChainElement(this);
    }

    @Override
    public ChainElement copyWithoutSnapshot() {
        ContainerChainElement element = new ContainerChainElement(this);
        element.getElements().clear();
        return element;
    }

    public Map<String, ChainElement> extractAllChildElements() {
        Map<String, ChainElement> resultElements = new LinkedHashMap<>();

        if (getElements() != null) {
            for (ChainElement childElement : getElements()) {
                if (childElement instanceof ContainerChainElement) {
                    resultElements.putAll(((ContainerChainElement) childElement).extractAllChildElements());
                }
                resultElements.put(childElement.getId(), childElement);
            }
        }
        return resultElements;
    }
}
