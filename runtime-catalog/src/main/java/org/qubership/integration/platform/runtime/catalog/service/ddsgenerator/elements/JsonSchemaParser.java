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

package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.ElementDDSConverterException;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class JsonSchemaParser {

    public static final String UNKNOWN_TYPE = "unknown type";
    private final ObjectMapper jsonMapper;

    @Autowired
    public JsonSchemaParser(@Qualifier("primaryObjectMapper") ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public TemplateSchema toProperties(String jsonSchema) {
        if (StringUtils.isNotEmpty(jsonSchema)) {
            try {
                JsonNode root = jsonMapper.readTree(jsonSchema);
                return toProperties(root);
            } catch (JsonProcessingException e) {
                log.error("Failed to parse JSON Schema: {}...", jsonSchema.substring(0, 200), e);
                throw new ElementDDSConverterException("Failed to parse JSON Schema for element: "
                                                       + jsonSchema.substring(0, 200) + "...", e);
            }
        }
        return TemplateSchema.EMPTY;
    }

    public TemplateSchema toProperties(JsonNode schemaRoot) {
        return toProperties(schemaRoot, new ArrayList<>(), new HashMap<>());
    }

    private TemplateSchema toProperties(JsonNode schemaRoot,
                                        List<Map<String, String>> resultProperties,
                                        Map<String, List<Map<String, String>>> resultDefinitions) {
        if (!isEmptyNode(schemaRoot)) {
            JsonNode schemeProperties = schemaRoot.path("properties");
            JsonNode arrayItems = schemaRoot.path("items");
            parseProperties(schemaRoot, resultProperties, schemeProperties, arrayItems);

            parseDefinitions(resultDefinitions, schemaRoot.path("$defs"));
            parseDefinitions(resultDefinitions, schemaRoot.path("definitions"));
        }
        return new TemplateSchema(resultProperties, resultDefinitions);
    }

    private void parseDefinitions(Map<String, List<Map<String, String>>> resultDefinitions, JsonNode definitions) {
        if (!isEmptyNode(definitions)) {
            for (Iterator<String> it = definitions.fieldNames(); it.hasNext();) {
                String definitionName = it.next();
                ArrayList<Map<String, String>> definitionProperties = new ArrayList<>();
                resultDefinitions.put(definitionName, definitionProperties);
                toProperties(definitions.get(definitionName), definitionProperties, resultDefinitions);
            }
        }
    }

    private void parseProperties(JsonNode schemaRoot, List<Map<String, String>> resultProperties,
                                 JsonNode schemeProperties, JsonNode arrayItems) {
        if (isEmptyNode(schemeProperties)) {
            Set<String> requiredSet = parseRequiredToSet(schemaRoot.path("required"));
            // primitive type or array in body
            parseSchemeProperty(isEmptyNode(arrayItems) ? "—" : "[]", schemaRoot, "", requiredSet, resultProperties);
        } else {
            fillPropertiesMap(schemeProperties, schemaRoot.path("required"), "", resultProperties);
        }
    }

    private void fillPropertiesMap(JsonNode properties, JsonNode requiredList, String path, List<Map<String, String>> result) {
        Set<String> requiredSet = parseRequiredToSet(requiredList);

        for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            JsonNode property = properties.get(name);
            parseSchemeProperty(name, property, path, requiredSet, result);
        }
    }

    private @NotNull Set<String> parseRequiredToSet(JsonNode requiredList) {
        Set<String> requiredSet = new HashSet<>();
        if (requiredList != null && !(requiredList instanceof NullNode) && !(requiredList instanceof MissingNode)) {
            for (JsonNode required : requiredList) {
                requiredSet.add(required.asText());
            }
        }
        return requiredSet;
    }

    private void parseSchemeProperty(String name, JsonNode property, String path, Set<String> requiredFields,
                                     List<Map<String, String>> result) {
        String baseType = detectPropertyBaseType(property);
        String composition = detectComposition(property);
        boolean hasComposition = StringUtils.isNotEmpty(composition);
        String compositionType = buildCompositionType(hasComposition, baseType, composition);

        if (baseType.equals("array")) {
            JsonNode items = property.path("items");
            if (!isEmptyNode(items) && !hasComposition) {
                String arrayType = detectPropertyBaseType(items);
                writeResult(name, property, path, requiredFields, result, "array of " + arrayType);
                if (arrayType.equals("array") || arrayType.equals("object")) {
                    fillPropertiesMap(items.path("properties"), items.path("required"), path + name + ".", result);
                }
            } else {
                writeResult(name, property, path, requiredFields, result, compositionType);
            }
        } else if (baseType.equals("object")) {
            writeResult(name, property, path, requiredFields, result, compositionType);
            if (!hasComposition) {
                fillPropertiesMap(property.path("properties"), property.path("required"), path + name + ".", result);
            }
        } else {
            writeResult(name, property, path, requiredFields, result, hasComposition ? compositionType : baseType);
        }
    }

    private static String buildCompositionType(boolean hasComposition, @Nullable String type, String composition) {
        return hasComposition ? ((type == null || UNKNOWN_TYPE.equals(type) ? "" : (type + " ")) + composition + "[...]") : type;
    }

    private String detectComposition(JsonNode property) {
        JsonNode allOf = property.path("allOf");
        if (!isEmptyNode(allOf)) {
            return "allOf";
        }

        JsonNode oneOf = property.path("oneOf");
        if (!isEmptyNode(oneOf)) {
            return "oneOf";
        }

        JsonNode anyOf = property.path("anyOf");
        if (!isEmptyNode(anyOf)) {
            return "anyOf";
        }

        JsonNode not = property.path("not");
        if (!isEmptyNode(not)) {
            return "not";
        }

        return null;
    }

    private @NotNull String detectPropertyBaseType(JsonNode property) {
        String type = property.path("type").asText().toLowerCase();
        if (!StringUtils.isEmpty(type)) {
            return type;
        }

        if (property.path("type") instanceof ArrayNode typeList) {
            try {
                return jsonMapper.writeValueAsString(typeList).replace("\"", "");
            } catch (JsonProcessingException e) {
                return UNKNOWN_TYPE;
            }
        }

        String ref = property.path("$ref").asText();
        if (!StringUtils.isEmpty(ref)) {
            return "reference to " + ref;
        }

        JsonNode enumNode = property.path("enum");
        if (!isEmptyNode(enumNode)) {
            return "enum";
        }

        JsonNode constNode = property.path("const");
        if (!isEmptyNode(constNode)) {
            return "const";
        }

        return UNKNOWN_TYPE;
    }

    private void writeResult(String name, JsonNode property, String path, Set<String> requiredFields, List<Map<String, String>> result, String type) {
        String description = property.path("description").asText();
        Map<String, String> props = new HashMap<>();
        props.put("name", path + name);
        props.put("optionality", requiredFields.contains(name) ? "mandatory" : "optional");
        props.put("type", type);
        props.put("description", StringUtils.isEmpty(description) ? null : description);
        result.add(props);
    }

    private boolean isEmptyNode(JsonNode schemeProperties) {
        return schemeProperties == null || schemeProperties instanceof NullNode || schemeProperties instanceof MissingNode;
    }
}
