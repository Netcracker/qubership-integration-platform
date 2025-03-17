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

package org.qubership.integration.platform.runtime.catalog.testutils.mapper;

import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.catalog.model.library.ElementType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.catalog.util.DistinctByKey;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.ChainElementImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.DependencyImportDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.service.ElementService.CONTAINER_TYPE_NAME;

@TestComponent
public class ChainElementsMapper implements ImportDTOMapper<List<ChainElement>, ElementsDTO> {

    private final LibraryElementsService libraryService;

    @Autowired
    public ChainElementsMapper(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    @Override
    public List<ChainElement> toEntity(ElementsDTO elementsDTO) {
        List<Pair<ChainElementImportDTO, ElementDescriptor>> sortedElementDTOS = elementsDTO.getElementImportDTOS().stream()
                .map(elementDTO -> Pair.of(elementDTO, libraryService.getElementDescriptor(elementDTO.getType())))
                .sorted((left, right) -> {
                    ElementType leftType = Optional.ofNullable(left.getValue())
                            .map(ElementDescriptor::getType)
                            .orElse(null);
                    ElementType rightType = Optional.ofNullable(right.getValue())
                            .map(ElementDescriptor::getType)
                            .orElse(null);
                    if (rightType == null || leftType == rightType) {
                        return 0;
                    }
                    return ElementType.SWIMLANE == rightType ? -1 : 1;
                })
                .toList();
        Map<String, ChainElement> elements = new HashMap<>();
        for (Pair<ChainElementImportDTO, ElementDescriptor> dtoPair : sortedElementDTOS) {
            createEntity(dtoPair, elements);
        }
        enrichWithDependencies(elements, elementsDTO.getDependencyImportDTOS());
        return new ArrayList<>(elements.values());
    }

    @Override
    public ElementsDTO toDto(List<ChainElement> elements) {
        List<DependencyImportDTO> dependencyImportDTOS = elements.stream()
                .flatMap(element -> element instanceof ContainerChainElement container
                        ? container.extractAllChildElements().values().stream()
                        : Stream.of(element))
                .flatMap(element -> Stream.concat(
                        element.getInputDependencies().stream(),
                        element.getOutputDependencies().stream()))
                .filter(DistinctByKey.newInstance(Dependency::getId))
                .map(dependency ->
                        new DependencyImportDTO(dependency.getElementFrom().getId(), dependency.getElementTo().getId()))
                .collect(Collectors.toList());
        List<ChainElementImportDTO> elementImportDTOS = elements.stream()
                .filter(element -> element.getParent() == null)
                .map(this::createDtoFromElement)
                .collect(Collectors.toList());
        return new ElementsDTO(elementImportDTOS, dependencyImportDTOS);
    }

    protected ChainElement createEntity(Pair<ChainElementImportDTO, ElementDescriptor> dtoPair, Map<String, ChainElement> elements) {
        ChainElementImportDTO elementDTO = dtoPair.getKey();
        ElementDescriptor descriptor = Optional.ofNullable(dtoPair.getValue())
                .orElseGet(() -> {
                    if (CONTAINER_TYPE_NAME.equals(elementDTO.getType())) {
                        ElementDescriptor containerDescriptor = new ElementDescriptor();
                        containerDescriptor.setType(ElementType.CONTAINER);
                        containerDescriptor.setContainer(true);
                        return containerDescriptor;
                    }
                    throw new IllegalArgumentException("Element of type " + elementDTO.getType() + " not found");
                });

        ChainElement element;
        if (descriptor.isContainer()) {
            ContainerChainElement containerElement = new ContainerChainElement();
            for (ChainElementImportDTO childDTO : elementDTO.getChildren()) {
                ElementDescriptor childDescriptor = libraryService.getElementDescriptor(childDTO.getType());
                ChainElement childEntity = createEntity(Pair.of(childDTO, childDescriptor), elements);
                containerElement.addChildElement(childEntity);
            }
            element = containerElement;
        } else if (ElementType.SWIMLANE == descriptor.getType()) {
            element = new SwimlaneChainElement();
        } else {
            element = new ChainElement();
        }
        element.setId(elementDTO.getId());
        element.setType(elementDTO.getType());
        element.setName(elementDTO.getName());
        element.setDescription(elementDTO.getDescription());
        element.setOriginalId(elementDTO.getOriginalId());
        element.setEnvironment(elementDTO.getServiceEnvironment());
        element.setProperties(elementDTO.getProperties());
        element.setCreatedWhen(new Timestamp(System.currentTimeMillis()));
        if (elements.get(elementDTO.getSwimlaneId()) instanceof SwimlaneChainElement swimlane) {
            swimlane.addElement(element);
        }
        elements.put(element.getId(), element);
        return element;
    }

    private void enrichWithDependencies(Map<String, ChainElement> elements, List<DependencyImportDTO> dependencyImportDTOS) {
        for (DependencyImportDTO dependencyDTO : dependencyImportDTOS) {
            ChainElement elementFrom = elements.get(dependencyDTO.getFrom());
            ChainElement elementTo = elements.get(dependencyDTO.getTo());
            if (elementFrom == null || elementTo == null) {
                throw new IllegalArgumentException(
                        "Unable to create dependency. At least one element not found: " + dependencyDTO);
            }
            Dependency dependency = Dependency.of(elementFrom, elementTo);
            elementFrom.addOutputDependency(dependency);
            elementTo.addInputDependency(dependency);
        }
    }

    private ChainElementImportDTO createDtoFromElement(ChainElement element) {
        List<ChainElementImportDTO> childrenDTO = new ArrayList<>();
        if (element instanceof ContainerChainElement containerElement) {
            for (ChainElement child : containerElement.getElements()) {
                childrenDTO.add(createDtoFromElement(child));
            }
        }
        return ChainElementImportDTO.builder()
                .id(element.getId())
                .type(element.getType())
                .name(element.getName())
                .description(element.getDescription())
                .children(childrenDTO)
                .swimlaneId(Optional.ofNullable(element.getSwimlane()).map(ChainElement::getId).orElse(null))
                .originalId(element.getOriginalId())
                .serviceEnvironment(element.getEnvironment())
                .properties(element.getProperties())
                .build();
    }
}
