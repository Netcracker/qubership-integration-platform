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

package org.qubership.integration.platform.runtime.catalog.model;

import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.util.DistinctByKey;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
public class ChainDiff {

    private List<ChainElement> createdElements;
    private List<ChainElement> updatedElements;
    private List<ChainElement> removedElements;
    @Setter
    private String createdDefaultSwimlaneId;
    @Setter
    private String createdReuseSwimlaneId;
    private List<Dependency> createdDependencies;
    private List<Dependency> removedDependencies;

    public ChainDiff() {
        this.createdElements = new ArrayList<>();
        this.updatedElements = new ArrayList<>();
        this.removedElements = new ArrayList<>();
        this.createdDependencies = new ArrayList<>();
        this.removedDependencies = new ArrayList<>();
    }

    public void addCreatedElement(ChainElement chainElement) {
        addIfNotPresent(createdElements, chainElement);
    }

    public void addCreatedElements(List<ChainElement> chainElements) {
        chainElements.forEach(element -> addIfNotPresent(createdElements, element));
    }

    public void addUpdatedElement(ChainElement chainElement) {
        addIfNotPresent(updatedElements, chainElement);
    }

    public void addUpdatedElements(List<ChainElement> chainElements) {
        chainElements.forEach(element -> addIfNotPresent(updatedElements, element));
    }

    public void addRemovedElement(ChainElement chainElement) {
        addIfNotPresent(removedElements, chainElement);
    }

    public void addRemovedElements(List<ChainElement> chainElements) {
        chainElements.forEach(element -> addIfNotPresent(removedElements, element));
    }

    public void addCreatedDependency(Dependency dependency) {
        addIfNotPresent(createdDependencies, dependency);
    }

    public void addCreatedDependencies(List<Dependency> dependencies) {
        dependencies.forEach(dependency -> addIfNotPresent(createdDependencies, dependency));
    }

    public void addRemovedDependency(Dependency dependency) {
        addIfNotPresent(removedDependencies, dependency);
    }

    public void addRemovedDependencies(List<Dependency> dependencies) {
        dependencies.forEach(dependency -> addIfNotPresent(removedDependencies, dependency));
    }

    public void merge(ChainDiff chainDelta) {
        this.createdElements = mergeElements(this.createdElements, chainDelta.createdElements);
        this.updatedElements = mergeElements(this.updatedElements, chainDelta.updatedElements);
        this.removedElements = mergeElements(this.removedElements, chainDelta.removedElements);
        this.createdDependencies = mergeDependencies(this.createdDependencies, chainDelta.createdDependencies);
        this.removedDependencies = mergeDependencies(this.removedDependencies, chainDelta.removedDependencies);
    }

    private void addIfNotPresent(List<ChainElement> elements, ChainElement newElement) {
        if (!elements.contains(newElement)
                || elements.stream().noneMatch(it -> it.getId().equals(newElement.getId()))) {
            elements.add(newElement);
        }
    }

    private void addIfNotPresent(List<Dependency> dependencies, Dependency newDependency) {
        if (!dependencies.contains(newDependency)
                || dependencies.stream().noneMatch(it -> it.getId().equals(newDependency.getId()))) {
            dependencies.add(newDependency);
        }
    }

    private List<ChainElement> mergeElements(List<ChainElement> right, List<ChainElement> left) {
        return Stream.concat(right.stream(), left.stream())
                .filter(DistinctByKey.newInstance(ChainElement::getId))
                .collect(Collectors.toList());
    }

    private List<Dependency> mergeDependencies(List<Dependency> right, List<Dependency> left) {
        return Stream.concat(right.stream(), left.stream())
                .filter(DistinctByKey.newInstance(Dependency::getId))
                .collect(Collectors.toList());
    }
}
