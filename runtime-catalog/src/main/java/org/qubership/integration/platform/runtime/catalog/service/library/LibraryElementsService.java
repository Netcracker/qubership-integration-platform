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

package org.qubership.integration.platform.runtime.catalog.service.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.ElementDescriptorProperties;
import org.qubership.integration.platform.runtime.catalog.model.dto.chain.ElementsFilterDTO;
import org.qubership.integration.platform.runtime.catalog.model.library.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LibraryElementsService {
    private final ObjectMapper yamlMapper;
    private final PropertyPlaceholderHelper propertyPlaceholderHelper;
    private final Properties descriptorProperties;

    private final Map<String, ElementProperty> elementsProperties = new HashMap<>();

    private final Map<String, ElementDescriptor> elements = new HashMap<>();
    private final Map<String, ElementFolder> folders = new HashMap<>();
    private final Map<String, JsonNode> elementPatches = new HashMap<>();

    public LibraryElementsService(
            @Qualifier("defaultYamlMapper") YAMLMapper defaultYamlMapper,
            PropertyPlaceholderHelper propertyPlaceholderHelper,
            ElementDescriptorProperties elementDescriptorProperties
    ) {
        this.yamlMapper = defaultYamlMapper;
        this.propertyPlaceholderHelper = propertyPlaceholderHelper;
        this.descriptorProperties = elementDescriptorProperties.getProperties();
    }

    public void registerFolder(ElementFolder folder) {
        this.folders.put(folder.getName(), folder);
    }

    public void registerFolders(List<ElementFolder> folders) {
        this.folders.putAll(folders.stream()
                .collect(Collectors.toMap(ElementFolder::getName, Function.identity())));
    }

    public void loadFoldersDescriptor(InputStream inputStream) throws IOException {
        try (Reader reader = new InputStreamReader(inputStream)) {
            List<ElementFolder> parsedFolders = Arrays.asList(yamlMapper.readValue(reader, ElementFolder[].class));
            this.registerFolders(parsedFolders);
        }
    }

    public void loadElementPatch(String elementName, InputStream inputStream) throws IOException {
        try (Reader reader = new InputStreamReader(inputStream)) {
            JsonNode elementPatchNode = yamlMapper.readTree(reader);
            elementPatches.put(elementName, elementPatchNode);
        }
    }

    public ElementFolder getFolder(String name) {
        return folders.get(name);
    }

    public void registerElement(ElementDescriptor elementDescriptor) {
        this.elements.put(elementDescriptor.getName(), elementDescriptor);
        // TODO change to map <chainElement, <propertyName, propertyDescriptor>>
        elementsProperties.putAll(elementDescriptor.getProperties().getAll().stream()
                .collect(Collectors.toMap(ElementProperty::getName, Function.identity())));
    }

    public ElementDescriptor loadElementDescriptor(String elementName, InputStream inputStream) throws IOException {
        try (Reader reader = new InputStreamReader(inputStream)) {
            String descriptorYaml = propertyPlaceholderHelper.replacePlaceholders(IOUtils.toString(reader), descriptorProperties);
            JsonNode descriptorNode = yamlMapper.readTree(descriptorYaml);
            JsonNode elementPatchNode = elementPatches.get(elementName);
            if (elementPatchNode != null) {
                try {
                    JsonPatch jsonPatch = JsonPatch.fromJson(elementPatchNode);
                    descriptorNode = jsonPatch.apply(descriptorNode);
                } catch (JsonPatchException e) {
                    log.error("Failed to apply patch for element descriptor: {}", elementName, e);
                }
            }
            ElementDescriptor elementDescriptor = yamlMapper.convertValue(descriptorNode, ElementDescriptor.class);
            if (elementDescriptor != null) {
                this.registerElement(elementDescriptor);
            }
            return elementDescriptor;
        }
    }

    public ElementDescriptor getElementDescriptor(String name) {
        return elements.get(name);
    }

    public ElementDescriptor getElementDescriptor(ChainElement element) {
        return elements.getOrDefault(element.getType(), new ElementDescriptor());
    }

    public List<ElementDescriptor> getElementDescriptorsByType(ElementType type) {
        return elements.values().stream()
                .filter(element -> element.getType() == type)
                .collect(Collectors.toList());
    }

    public LibraryElements getElementsHierarchy() {
        LibraryElements root = new LibraryElements();
        Map<String, LibraryElementGroup> groups = this.folders.values().stream()
                .map(LibraryElementGroup::new)
                .collect(Collectors.toMap(group -> group.getFolder().getName(), Function.identity()));
        for (LibraryElementGroup group : groups.values()) {
            String parentFolder = group.getFolder().getParent();
            if (StringUtils.isNotBlank(parentFolder) && groups.containsKey(parentFolder)) {
                groups.get(parentFolder).addChild(group);
            } else {
                root.addChild(group);
            }
        }
        for (ElementDescriptor element : elements.values()) {
            String parentFolder = element.getFolder();
            if (StringUtils.isNotBlank(parentFolder) && groups.containsKey(parentFolder) && element.getParentRestriction().isEmpty()) {
                groups.get(parentFolder).addChild(element);
            } else {
                if (element.getParentRestriction().isEmpty()) {
                    root.addChild(element);
                } else {
                    root.addChild(element.getName(), element);
                }
            }
        }
        return root;
    }

    public List<String> getTriggerElementNames() {
        return elements.values().stream().filter(element -> element.getType().equals(ElementType.TRIGGER))
                .map(ElementDescriptor::getName).collect(Collectors.toList());
    }

    public Map<String, ElementDescriptor> getElementsWithReferenceProperties() {
        return elements.values().stream()
                .filter(element -> !element.getReferenceProperties().isEmpty())
                .collect(Collectors.toMap(ElementDescriptor::getName, Function.identity()));
    }

    public List<ElementsFilterDTO> getElementsTitles(List<String> types) {
        return types.stream().filter(type -> !("container").equals(type))
                .map(type -> new ElementsFilterDTO(this.elements.get(type).getTitle(), type))
                .toList();
    }

    public List<String> getDeprecatedElementsNames() {
        return elements.values().stream()
                .filter(ElementDescriptor::isDeprecated)
                .map(ElementDescriptor::getName)
                .toList();
    }
}
