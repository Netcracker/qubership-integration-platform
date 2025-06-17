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

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.library.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ElementUtils {

    private static final String CREATED_ELEMENT_ID_PLACEHOLDER = "%%{created-element-id-placeholder}";
    private static final String CHAIN_ID_PLACEHOLDER = "%%{chain-id-placeholder}";

    private final LibraryElementsService libraryService;

    @Autowired
    public ElementUtils(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    public static String replaceDefaultValuePlaceholders(String value, String elementId,
        String chainId) {
        return value.replace(CREATED_ELEMENT_ID_PLACEHOLDER, elementId)
            .replace(CHAIN_ID_PLACEHOLDER, chainId);
    }

    public static Map<String, Object> extractOperationAsyncProperties(Map<String, Object> properties) {
        return (Map<String, Object>) properties.getOrDefault(
            CamelNames.OPERATION_ASYNC_PROPERTIES, Collections.emptyMap());
    }

    public static Map<String, Object> extractGrpcProperties(Map<String, Object> properties) {
        return (Map<String, Object>) properties.getOrDefault(CamelNames.GRPC_PROPERTIES, Collections.emptyMap());
    }

    public static Map<String, Object> extractServiceCallProperties(Map<String, Object> properties) {
        return (Map<String, Object>) properties.getOrDefault(CamelNames.SERVICE_CALL_ADDITIONAL_PARAMETERS, Collections.emptyMap());
    }


    /**
     * @param primary - overrides secondary parameters in case of conflict and if value non-empty
     * @param secondary
     * @return merged properties
     */
    public static Map<String, Object> mergeProperties(Map<String, Object> primary, Map<String, Object> secondary) {
        Map<String, Object> merged = new HashMap<>(secondary);
        merged.putAll(primary);
        return merged.entrySet().stream()
                .filter(prop -> StringUtils.isNotEmpty((CharSequence) prop.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Create a copy of elements with separated composite triggers
     */
    public List<ChainElement> splitCompositeTriggers(List<ChainElement> elements) {
        List<ChainElement> newElements = new ArrayList<>(elements);
        for (ChainElement element : elements) {
            ElementDescriptor descriptor = libraryService.getElementDescriptor(element);
            if (descriptor != null && descriptor.getType() == ElementType.COMPOSITE_TRIGGER) {
                boolean elementHasNoParent = element.getParent() == null || CamelNames.CONTAINER.equals(element.getParent().getType());
                if (elementHasNoParent && element.getInputDependencies().isEmpty()) {
                    throw new SnapshotCreationException("Element must contains at least one input dependency.", element);
                }

                ChainElement trigger = element.copyWithOriginalId();
                trigger.setId(convertToAnotherUUID(element.getId()));
                trigger.getOutputDependencies().addAll(element.getOutputDependencies());
                newElements.add(trigger);
            }
        }
        return newElements;
    }

    /**
     * Idempotent uuid generation from existing id
     */
    public String convertToAnotherUUID(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes()).toString();
    }

    /**
     * See {@link ElementProperty#isResetValueOnCopy()}
     */
    public void updateResetOnCopyProperties(ChainElement copy) {
        updateResetOnCopyProperties(copy, copy.getChain().getId());
    }

    public void updateResetOnCopyProperties(ChainElement copy, String chainId) {
        Map<String, Object> copyProperties = copy.getProperties();
        ElementDescriptor descriptor = libraryService.getElementDescriptor(copy.getType());
        if (null != descriptor) {
            for (ElementProperty elementProperty : descriptor.getProperties().getAll()) {
                if (elementProperty.isResetValueOnCopy()) {
                    copyProperties.put(
                        elementProperty.getName(),
                        replaceDefaultValuePlaceholders(elementProperty.getDefaultValue(),
                            copy.getId(), chainId));
                }
            }
        }
    }

    public boolean areMandatoryPropertiesPresent(@NonNull ChainElement element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element);
        if (descriptor == null) {
            return true;
        }

        for (CustomTab customTab : descriptor.getCustomTabs()) {
            if (customTab.getValidation() != null
                    && !customTab.getValidation().arePropertiesValid(element.getProperties())) {
                return false;
            }
        }
        for (ElementProperty propertyDescriptor : descriptor.getProperties().getAll()) {
            if (!isMandatoryPropertyPresent(propertyDescriptor, element)) {
                return false;
            }
        }
        return true;
    }

    public boolean isMandatoryPropertyPresent(@NonNull ElementProperty propertyDescriptor, @NonNull ChainElement element) {
        if (propertyDescriptor.getType() == PropertyValueType.CUSTOM && propertyDescriptor.getValidation() != null) {
            return propertyDescriptor.getValidation().arePropertiesValid(element.getProperties());
        }

        return !propertyDescriptor.isMandatory() || ObjectUtils.isNotEmpty(element.getProperty(propertyDescriptor.getName()));
    }

    public static String buildRouteVariableName(ChainElement element) {
        return "route-" + element.getOriginalId();
    }

    public boolean isMandatoryInnerElementPresent(@NonNull ChainElement element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element);
        if (descriptor == null || !descriptor.isMandatoryInnerElement()) {
            return true;
        }

        if (element instanceof ContainerChainElement container) {
            boolean hasAtLeastOneStartElement = container.getElements().stream()
                    .anyMatch(child -> child.getInputDependencies().isEmpty());
            if (!hasAtLeastOneStartElement) {
                return false;
            }
        } else if (element.getOutputDependencies().isEmpty()) {
            return false;
        }

        return true;
    }
}
