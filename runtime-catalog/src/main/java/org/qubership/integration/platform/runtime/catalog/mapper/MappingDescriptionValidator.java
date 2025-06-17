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

package org.qubership.integration.platform.runtime.catalog.mapper;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.DataType;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.ReferenceType;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MappingDescriptionValidator {

    private static final String NO_MAPPING_FOR_REQUIRED_ATTRIBUTES_ERROR_MESSAGE = "No mapping for structure with required attributes.";
    private static final String MANDATORY_FIELDS_MISSING_IN_MAPPING_ERROR_MESSAGE = "Mandatory fields are missing in current mapping.";


    public void validate(MappingDescription mappingDescription) {
        validateMandatoryFields(mappingDescription);
    }

    /**
     * Validation that all fields which marked as required ({@link Attribute#required required }) in output ({@link MappingDescription#target target}) model
     * participate in mapping ({@link MappingDescription#actions actions})
     *
     * @param mappingDescription input mapping description
     * @throws SnapshotCreationException when one or move required fields are not mapped
     */
    private void validateMandatoryFields(MappingDescription mappingDescription) {
        //Collect all mandatoryAttributes with path
        Map<Integer, LinkedList<String>> pathsToMandatoryAttributes = new HashMap<>();

        //Headers
        pathsToMandatoryAttributes.putAll(getMandatoryHeaders(mappingDescription));

        //Properties
        pathsToMandatoryAttributes.putAll(getMandatoryProperties(mappingDescription));

        //Target body
        pathsToMandatoryAttributes.putAll(getMandatoryBody(mappingDescription));

        if (!pathsToMandatoryAttributes.isEmpty() && mappingDescription.getActions().isEmpty()) {
            throw new SnapshotCreationException(NO_MAPPING_FOR_REQUIRED_ATTRIBUTES_ERROR_MESSAGE);
        }

        if (!pathsToMandatoryAttributes.isEmpty()) {
            //Collect all mapping relation path
            List<List<String>> targetPaths = mappingDescription
                    .getActions()
                    .stream()
                    .map(mappingAction -> mappingAction.getTarget().getPath())
                    .toList();

            //Check mandatory paths in existing mappings
            List<Integer> missingMandatoryAttributes = pathsToMandatoryAttributes
                    .keySet()
                    .stream()
                    .filter(attrId -> {
                        List<String> attrPaths = pathsToMandatoryAttributes.get(attrId);
                        return targetPaths
                                .stream()
                                .noneMatch(targetPath -> Arrays.equals(targetPath.toArray(), attrPaths.toArray()));
                    })
                    .toList();

            if (!missingMandatoryAttributes.isEmpty()) {
                throw new SnapshotCreationException(MANDATORY_FIELDS_MISSING_IN_MAPPING_ERROR_MESSAGE);
            }
        }
    }

    private Map<Integer, LinkedList<String>> getMandatoryHeaders(MappingDescription mappingDescription) {
        return mappingDescription
                .getTarget()
                .getHeaders()
                .stream()
                .filter(Attribute::getRequired)
                .map(Attribute::getId)
                .collect(Collectors.toMap(String::hashCode, attribute -> new LinkedList<>()));
    }

    private Map<Integer, LinkedList<String>> getMandatoryProperties(MappingDescription mappingDescription) {
        Collection<Attribute> targetProperties = mappingDescription.getTarget().getProperties();
        Map<Integer, LinkedList<String>> pathsToMandatoryProperties = new HashMap<>();

        collectMandatoryAttributes(targetProperties, pathsToMandatoryProperties, new LinkedList<>(), new HashMap<>());

        return pathsToMandatoryProperties;
    }

    private Map<Integer, LinkedList<String>> getMandatoryBody(MappingDescription mappingDescription) {
        Collection<Attribute> targetAttributes = mappingDescription.getTarget().getBody().getNestedAttributes();
        Map<Integer, LinkedList<String>> pathToMandatoryAttributes = new HashMap<>();

        collectMandatoryAttributes(targetAttributes, pathToMandatoryAttributes, new LinkedList<>(), mappingDescription.getTarget().getBodyDefinitions());

        return pathToMandatoryAttributes;

    }

    private void collectMandatoryAttributes(Collection<Attribute> allAttributes,
                                            Map<Integer, LinkedList<String>> mandatoryAttributes,
                                            LinkedList<String> currentPath,
                                            Map<String, DataType> attributeDefinitions) {
        boolean mandatoryAttributeFound;
        for (Attribute attribute : allAttributes) {
            DataType resolvedDataType = null;
            mandatoryAttributeFound = attribute.getRequired();

            if (attribute.getType() instanceof ReferenceType attributeReferenceType) {
                resolvedDataType = attributeDefinitions.getOrDefault(attributeReferenceType.getDefinitionId(), null);
            }

            Collection<Attribute> nestedAttributes = resolvedDataType != null ? resolvedDataType.getNestedAttributes() : attribute.getType().getNestedAttributes();

            if (!nestedAttributes.isEmpty()) {
                mandatoryAttributeFound = false;
                currentPath.add(attribute.getId());
                collectMandatoryAttributes(nestedAttributes, mandatoryAttributes, currentPath, attributeDefinitions);

            }

            if (mandatoryAttributeFound) {
                currentPath.add(attribute.getId());
                mandatoryAttributes.put(currentPath.hashCode(), new LinkedList<>(currentPath));
                currentPath.clear();
            }
        }
    }

}
