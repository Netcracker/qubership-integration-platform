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

import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.system.ServiceEnvironment;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainElementsExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.library.constants.CamelNames.CONTAINER;


@Component
public class ChainElementsExternalEntityMapper {

    private final LibraryElementsService libraryService;
    private final ChainElementFilePropertiesSubstitutor chainElementFilePropertiesSubstitutor;

    public ChainElementsExternalEntityMapper(
            LibraryElementsService libraryService,
            ChainElementFilePropertiesSubstitutor chainElementFilePropertiesSubstitutor
    ) {
        this.libraryService = libraryService;
        this.chainElementFilePropertiesSubstitutor = chainElementFilePropertiesSubstitutor;
    }

    /**
     * Builds the JPA element tree from the library element model.
     *
     * <p>The model list is flat: it holds every element, including container descendants. Container
     * nesting is rebuilt from {@link Element#getChildren()} starting at the root elements, and swimlane
     * membership is wired in a second pass once every element exists, so a member can resolve its lane
     * by id regardless of iteration order. Element property files are already substituted into the
     * model, so no file lookup happens here; {@code createdWhen} is re-stamped because the model does
     * not carry it.
     */
    public List<ChainElement> toInternalEntity(@NonNull Collection<Element> modelElements) {
        Map<String, ChainElement> resultElements = new LinkedHashMap<>();

        modelElements.stream()
                .filter(element -> element.getParent().isEmpty())
                .forEach(rootElement -> createInternalEntity(rootElement, resultElements));

        for (Element modelElement : modelElements) {
            modelElement.getSwimlane().ifPresent(modelSwimlane -> {
                ChainElement element = resultElements.get(modelElement.getId());
                if (element != null && resultElements.get(modelSwimlane.getId()) instanceof SwimlaneChainElement swimlane) {
                    element.setSwimlane(swimlane);
                }
            });
        }

        return resultElements.values().stream()
                .filter(element -> element.getParent() == null)
                .collect(Collectors.toList());
    }

    public ChainElementsExternalMapperEntity toExternalEntity(@NonNull List<ChainElement> chainElements) {
        Map<String, byte[]> propertyFiles = new HashMap<>();
        List<ChainElementExternalEntity> elementsExternalEntities = chainElements.stream()
                .filter(element -> element.getParent() == null)
                .map(element -> createExternalFromInternal(element, propertyFiles))
                .collect(Collectors.toList());
        return ChainElementsExternalMapperEntity.builder()
                .chainElementExternalEntities(elementsExternalEntities)
                .elementPropertyFiles(propertyFiles)
                .build();
    }

    private ChainElement createInternalEntity(Element modelElement, Map<String, ChainElement> resultElements) {
        ElementDescriptor descriptor = resolveDescriptor(modelElement.getType());

        ChainElement element;
        if (descriptor.isContainer()) {
            element = new ContainerChainElement();
        } else if (ElementType.SWIMLANE == descriptor.getType()) {
            element = new SwimlaneChainElement();
        } else {
            element = new ChainElement();
        }

        if (element instanceof ContainerChainElement containerElement) {
            for (Element childModel : modelElement.getChildren()) {
                containerElement.addChildElement(createInternalEntity(childModel, resultElements));
            }
        }

        element.setId(modelElement.getId());
        element.setType(modelElement.getType());
        element.setName(modelElement.getName());
        element.setDescription(modelElement.getDescription());
        element.setOriginalId(modelElement.getOriginalId().orElse(null));
        element.setEnvironment(toEnvironmentEntity(modelElement.getEnvironment().orElse(null)));
        element.setProperties(modelElement.getProperties());
        element.setCreatedWhen(new Timestamp(System.currentTimeMillis()));

        resultElements.put(element.getId(), element);
        return element;
    }

    private ElementDescriptor resolveDescriptor(String type) {
        return libraryService.lookupElementDescriptor(type)
                .orElseGet(() -> {
                    if (CONTAINER.equals(type)) {
                        ElementDescriptor containerDescriptor = new ElementDescriptor();
                        containerDescriptor.setType(ElementType.CONTAINER);
                        containerDescriptor.setContainer(true);
                        return containerDescriptor;
                    }
                    throw new IllegalArgumentException("Element of type " + type + " not found");
                });
    }

    private ServiceEnvironment toEnvironmentEntity(org.qubership.integration.platform.chain.model.ServiceEnvironment modelEnvironment) {
        if (modelEnvironment == null) {
            return null;
        }
        ServiceEnvironment environment = new ServiceEnvironment();
        environment.setId(modelEnvironment.getId());
        environment.setName(modelEnvironment.getName());
        environment.setDescription(modelEnvironment.getDescription());
        environment.setSystemId(modelEnvironment.getSystemId());
        environment.setAddress(modelEnvironment.getAddress());
        environment.setSourceType(modelEnvironment.getSourceType());
        environment.setProperties(modelEnvironment.getProperties());
        environment.setNotActivated(!modelEnvironment.isActivated());
        environment.setCreatedWhen(modelEnvironment.getCreatedWhen());
        environment.setModifiedWhen(modelEnvironment.getModifiedWhen());
        return environment;
    }

    private ChainElementExternalEntity createExternalFromInternal(ChainElement element, final Map<String, byte[]> propertyFiles) {
        List<ChainElementExternalEntity> childrenExternalEntities = new ArrayList<>();
        if (element instanceof ContainerChainElement containerElement) {
            for (ChainElement child : containerElement.getElements()) {
                childrenExternalEntities.add(createExternalFromInternal(child, propertyFiles));
            }
        }

        ChainElementExternalEntity externalElement = ChainElementExternalEntity.builder()
                .id(element.getId())
                .type(element.getType())
                .name(element.getName())
                .description(element.getDescription())
                .children(childrenExternalEntities)
                .swimlaneId(Optional.ofNullable(element.getSwimlane()).map(ChainElement::getId).orElse(null))
                .originalId(element.getOriginalId())
                .serviceEnvironment(element.getEnvironment())
                .properties(new TreeMap<>(preSortProperties(element.getProperties())))
                .build();

        propertyFiles.putAll(chainElementFilePropertiesSubstitutor.getElementPropertiesAsSeparateFiles(externalElement));

        return externalElement;
    }

    public static Map<String, Object> preSortProperties(Map<String, Object> properties) {
        // Only roles list should be sorted by now
        if (properties.containsKey("roles") && properties.get("roles") instanceof List<?>) {
            properties.put("roles", ((List<String>) properties.get("roles")).stream().sorted().toList());
        }
        return properties;
    }
}
