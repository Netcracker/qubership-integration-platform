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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.catalog.model.library.ElementType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainElementExternalEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainElementsExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.ExternalEntityMapper;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.catalog.model.constant.CamelNames.CONTAINER;

@Component
public class ChainElementsExternalEntityMapper implements ExternalEntityMapper<List<ChainElement>, ChainElementsExternalMapperEntity> {

    private final LibraryElementsService libraryService;
    private final ChainElementFilePropertiesSubstitutor chainElementFilePropertiesSubstitutor;

    public ChainElementsExternalEntityMapper(
            LibraryElementsService libraryService,
            ChainElementFilePropertiesSubstitutor chainElementFilePropertiesSubstitutor
    ) {
        this.libraryService = libraryService;
        this.chainElementFilePropertiesSubstitutor = chainElementFilePropertiesSubstitutor;
    }

    @Override
    public List<ChainElement> toInternalEntity(@NonNull ChainElementsExternalMapperEntity elementsExternalMapperEntity) {
        Map<String, ChainElement> resultElements = new HashMap<>();

        // convert swimlane elements
        elementsExternalMapperEntity
                .getChainElementExternalEntities()
                .stream()
                .map(externalEntity -> Pair.of(externalEntity, libraryService.getElementDescriptor(externalEntity.getType())))
                .filter(swimlaneDescriptorPair -> Optional.ofNullable(swimlaneDescriptorPair.getValue())
                        .map(descriptor -> descriptor.getType() == ElementType.SWIMLANE)
                        .orElse(false))
                .forEach(swimlaneDescriptorPair -> createInternalEntity(
                        swimlaneDescriptorPair,
                        elementsExternalMapperEntity.getChainFilesDirectory(),
                        resultElements
                ));
        // convert non-swimlane elements
        elementsExternalMapperEntity
                .getChainElementExternalEntities()
                .stream()
                .map(externalElement -> Pair.of(externalElement, libraryService.getElementDescriptor(externalElement.getType())))
                .filter(elementDescriptoPair -> {
                    ElementDescriptor descriptor = elementDescriptoPair.getValue();
                    return descriptor == null || descriptor.getType() != ElementType.SWIMLANE;
                })
                .forEach(elementDescriptorPair -> createInternalEntity(
                        elementDescriptorPair,
                        elementsExternalMapperEntity.getChainFilesDirectory(),
                        resultElements
                ));

        return resultElements.values().stream()
                .filter(element -> element.getParent() == null)
                .collect(Collectors.toList());
    }

    @Override
    public ChainElementsExternalMapperEntity toExternalEntity(@NonNull List<ChainElement> chainElements) {
        Map<String, byte[]> propertyFiles = new HashMap<>();
        List<ChainElementExternalEntity> elementsExternalEntities = chainElements.stream()
                .filter(element -> element.getParent() == null)
                .map(this::createExternalFromInternal)
                .peek(externalElement -> propertyFiles.putAll(
                        chainElementFilePropertiesSubstitutor.getElementPropertiesAsSeparateFiles(externalElement)))
                .collect(Collectors.toList());
        return ChainElementsExternalMapperEntity.builder()
                .chainElementExternalEntities(elementsExternalEntities)
                .elementPropertyFiles(propertyFiles)
                .build();
    }

    private ChainElement createInternalEntity(
            Pair<ChainElementExternalEntity, ElementDescriptor> elementDescriptorPair,
            File chainFilesDir,
            Map<String, ChainElement> resultElements
    ) {
        ChainElementExternalEntity elementExternalEntity = elementDescriptorPair.getKey();
        ElementDescriptor descriptor = Optional.ofNullable(elementDescriptorPair.getValue())
                .orElseGet(() -> {
                    if (CONTAINER.equals(elementExternalEntity.getType())) {
                        ElementDescriptor containerDescriptor = new ElementDescriptor();
                        containerDescriptor.setType(ElementType.CONTAINER);
                        containerDescriptor.setContainer(true);
                        return containerDescriptor;
                    }
                    throw new IllegalArgumentException("Element of type " + elementExternalEntity.getType() + " not found");
                });

        ChainElement element;
        if (descriptor.isContainer()) {
            element = new ContainerChainElement();
        } else if (ElementType.SWIMLANE == descriptor.getType()) {
            element = new SwimlaneChainElement();
        } else {
            element = new ChainElement();
        }

        if (element instanceof ContainerChainElement containerElement) {
            for (ChainElementExternalEntity childExternal : elementExternalEntity.getChildren()) {
                ElementDescriptor childDescriptor = libraryService.getElementDescriptor(childExternal.getType());
                ChainElement childEntity = createInternalEntity(
                        Pair.of(childExternal, childDescriptor), chainFilesDir, resultElements);
                containerElement.addChildElement(childEntity);
            }
        }

        element.setId(elementExternalEntity.getId());
        element.setType(elementExternalEntity.getType());
        element.setName(elementExternalEntity.getName());
        element.setDescription(elementExternalEntity.getDescription());
        element.setOriginalId(elementExternalEntity.getOriginalId());
        element.setEnvironment(elementExternalEntity.getServiceEnvironment());
        element.setProperties(elementExternalEntity.getProperties());
        element.setCreatedWhen(new Timestamp(System.currentTimeMillis()));

        if (resultElements.get(elementExternalEntity.getSwimlaneId()) instanceof SwimlaneChainElement swimlane) {
            element.setSwimlane(swimlane);
        }

        chainElementFilePropertiesSubstitutor
                .enrichElementWithFileProperties(element, chainFilesDir, elementExternalEntity.getPropertiesFilename());

        resultElements.put(element.getId(), element);
        return element;
    }

    private ChainElementExternalEntity createExternalFromInternal(ChainElement element) {
        List<ChainElementExternalEntity> childrenExternalEntities = new ArrayList<>();
        if (element instanceof ContainerChainElement containerElement) {
            for (ChainElement child : containerElement.getElements()) {
                childrenExternalEntities.add(createExternalFromInternal(child));
            }
        }

        return ChainElementExternalEntity.builder()
                .id(element.getId())
                .type(element.getType())
                .name(element.getName())
                .description(element.getDescription())
                .children(childrenExternalEntities)
                .swimlaneId(Optional.ofNullable(element.getSwimlane()).map(ChainElement::getId).orElse(null))
                .originalId(element.getOriginalId())
                .serviceEnvironment(element.getEnvironment())
                .properties(preSortProperties(element.getProperties()))
                .build();
    }

    public static Map<String, Object> preSortProperties(Map<String, Object> properties) {
        Map<String, Object> result = new LinkedHashMap<>(properties);
        // Only roles list should be sorted by now
        if (result.containsKey("roles") && result.get("roles") instanceof List<?>) {
            result.put("roles", ((List<String>) result.get("roles")).stream().sorted().toList());
        }
        return result;
    }
}
