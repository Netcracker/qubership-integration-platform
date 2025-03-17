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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.catalog.model.library.ElementType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.ElementDeserializationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.qubership.integration.platform.catalog.model.constant.CamelNames.CONTAINER;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils.toJsonPointer;

@Slf4j
@Component
@Deprecated(since = "2023.4")
public class ChainElementDeserializer extends StdDeserializer<ElementDeserializationResult> {

    private final LibraryElementsService libraryService;
    private final ElementRepository elementRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChainElementDeserializer(
            LibraryElementsService libraryService,
            ElementRepository elementRepository,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper
    ) {
        super((Class<?>) null);
        this.libraryService = libraryService;
        this.elementRepository = elementRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ElementDeserializationResult deserialize(JsonParser parser, DeserializationContext context) throws IOException {

        ChainElement resultElement;
        ElementDeserializationResult deserializationResult = new ElementDeserializationResult();
        YAMLMapper mapper = (YAMLMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);

        JsonNode idNode = node.get(ID);
        JsonNode nameNode = node.get(NAME);
        JsonNode descriptionNode = node.get(DESCRIPTION);
        String elementType = node.at(toJsonPointer(ELEMENT_TYPE)).asText();
        String swimlaneId = node.at(toJsonPointer(SWIMLANE_ID)).asText(null);
        JsonNode filenamePropertyNode = node.get(FILE_NAME_PROPERTY);
        ObjectNode propertiesNode = (ObjectNode) node.get(PROPERTIES);
        JsonNode childrenNode = node.get(CHILDREN);

        Optional<ChainElement> elementOptional = elementRepository.findById(idNode.asText());

        if (elementOptional.isPresent()) {
            resultElement = elementOptional.get();
            if (resultElement instanceof ContainerChainElement container) {
                container.getElements().clear();
            } else if (resultElement instanceof SwimlaneChainElement swimlane) {
                swimlane.getElements().clear();
            }
            resultElement.setModifiedWhen(new Timestamp(System.currentTimeMillis()));
        } else {
            if (!CONTAINER.equals(elementType)) {
                ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(elementType);
                if (elementDescriptor == null) {
                    throw new IllegalArgumentException("Element type " + elementType + " not found");
                }
                if (elementDescriptor.isContainer()) {
                    resultElement = new ContainerChainElement();
                } else if (ElementType.SWIMLANE == elementDescriptor.getType()) {
                    resultElement = new SwimlaneChainElement();
                } else {
                    resultElement = new ChainElement();
                }
            } else {
                resultElement = new ContainerChainElement();
            }
            resultElement.setId(idNode.asText());
            resultElement.setCreatedWhen(new Timestamp(System.currentTimeMillis()));
        }
        resultElement.setParent(null);
        resultElement.setName(nameNode != null ? nameNode.asText() : null);
        resultElement.setDescription(descriptionNode != null ? descriptionNode.asText() : null);
        resultElement.setType(elementType);
        resultElement.setModifiedWhen(Timestamp.valueOf(LocalDateTime.now()));

        if (filenamePropertyNode != null) {
            if (deserializationResult.getElementPropertiesFilenames() == null) {
                deserializationResult.setElementPropertiesFilenames(new HashMap<>());
            }
            deserializationResult.getElementPropertiesFilenames()
                    .put(resultElement.getId(), filenamePropertyNode.asText());
        }
        if (swimlaneId != null) {
            deserializationResult.getElementToSwimlaneRelations().put(resultElement.getId(), swimlaneId);
        }

        restoreProperties(propertiesNode, resultElement);

        deserializationResult.setElement(resultElement);

        restoreChildElements(childrenNode, deserializationResult, resultElement, mapper);

        return deserializationResult;
    }

    private void restoreProperties(ObjectNode propertiesNode, ChainElement resultElement) {
        Map<String, Object> elementProperties = new HashMap<>();
        if (propertiesNode != null) {
            elementProperties = objectMapper.convertValue(propertiesNode, new TypeReference<>(){});
        }

        resultElement.setProperties(elementProperties);
    }

    private void restoreChildElements(JsonNode childrenNode, ElementDeserializationResult resultDeserializeEntity,
                                      ChainElement resultElement, YAMLMapper mapper)
            throws JsonProcessingException {

        if (childrenNode != null && resultElement instanceof ContainerChainElement) {
            List<ChainElement> deserializedChildren = new ArrayList<>();

            for (final JsonNode childElementNode : childrenNode) {
                ElementDeserializationResult childDeserializationResult =
                        mapper.readValue(childElementNode.toString(), ElementDeserializationResult.class);
                ChainElement deserializedChild = childDeserializationResult.getElement();
                deserializedChildren.add(deserializedChild);
                if (!CollectionUtils.isEmpty(childDeserializationResult.getElementPropertiesFilenames())) {
                    if (resultDeserializeEntity.getElementPropertiesFilenames() == null) {
                        resultDeserializeEntity.setElementPropertiesFilenames(new HashMap<>());
                    }
                    resultDeserializeEntity.getElementPropertiesFilenames()
                            .putAll(childDeserializationResult.getElementPropertiesFilenames());
                }
                resultDeserializeEntity.getElementToSwimlaneRelations()
                        .putAll(childDeserializationResult.getElementToSwimlaneRelations());
            }

            ((ContainerChainElement) resultElement).addChildrenElements(deserializedChildren);
        }
    }

}
