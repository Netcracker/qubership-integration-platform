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

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity(name = "swimlane_elements")
public class SwimlaneChainElement extends ChainElement {

    @Builder.Default
    @OrderBy("id")
    @OneToMany(mappedBy = "swimlane", fetch = FetchType.LAZY)
    private List<ChainElement> elements = new LinkedList<>();

    @Column(name = "is_default_swimlane")
    private boolean defaultSwimlane = false;

    @Column(name = "is_reuse_swimlane")
    private boolean reuseSwimlane = false;

    protected SwimlaneChainElement(SwimlaneChainElement swimlane) {
        super(swimlane);
        this.defaultSwimlane = swimlane.defaultSwimlane;
        this.reuseSwimlane = swimlane.reuseSwimlane;
        setElements(new LinkedList<>());
    }

    public void addElement(ChainElement element) {
        element.setSwimlane(this);
        getElements().add(element);
    }

    public void addElements(List<ChainElement> elements) {
        getElements().forEach(element -> element.setSwimlane(this));
        getElements().addAll(elements);
    }

    public void removeElement(ChainElement element) {
        getElements().remove(element);
        element.setSwimlane(null);
    }

    public List<ChainElement> getRootElements() {
        return getElements().stream()
                .filter(element -> element.getParent() == null)
                .collect(Collectors.toList());
    }

    @Override
    public ChainElement copy() {
        return new SwimlaneChainElement(this);
    }

    @Override
    public ChainElement copyWithoutSnapshot() {
        SwimlaneChainElement element = new SwimlaneChainElement(this);
        element.getElements().clear();
        return element;
    }
}
