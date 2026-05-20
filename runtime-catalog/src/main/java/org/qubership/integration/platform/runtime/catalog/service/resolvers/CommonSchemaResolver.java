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

package org.qubership.integration.platform.runtime.catalog.service.resolvers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static org.qubership.integration.platform.runtime.catalog.service.schemas.SchemasConstants.*;


@Slf4j
public abstract class CommonSchemaResolver implements SchemaResolver {
    private static final String COMPONENTS_PREFIX = "#/components";
    private static final String DEFINITIONS_PREFIX = "#/definitions/";
    protected static final String SCHEMAS_PREFIX = "#/components/schemas/";
    protected static final String MESSAGES_PREFIX = "#/components/messages/";
    private static final String SCHEMA_ID_VALUE_DOMAIN = "http://system.catalog/schemas/";
    private static final String DEFAULT_SCHEMA_VALUE = "{\"$schema\": \"http://json-schema.org/draft-07/schema#\"}";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REF_FIELD_NAME = "$ref";
    private static final String ITEMS_FIELD_NAME = "items";
    private static final String PAYLOAD_FIELD_NAME = "payload";
    private static final String HEADERS_FIELD_NAME = "headers";
    private static final String EMPTY_STRING_REPLACEMENT = "";
    private static final String TYPE_FIELD_NAME = "type";
    private static final String OBJECT_FIELD_TYPE = "object";
    private static final String ARRAY_FIELD_TYPE = "array";
    private static final String EMPTY_REF = "#/";
    private static final String ADDITIONAL_PROPERTIES_FIELD_NAME = "additionalProperties";
    private static final String ALL_OF_FIELD_NAME = "allOf";
    private static final String ANY_OF_FIELD_NAME = "anyOf";


    @Override
    public abstract String resolveRef(String schemaRef, JsonNode componentsNode);

    protected ObjectNode getSchemaNode(String schemaRef, JsonNode componentsJsonNode) {
        ObjectNode resolvedSchema = OBJECT_MAPPER.createObjectNode();

        JsonNode componentNode = componentsJsonNode.at(schemaRef.replace(COMPONENTS_PREFIX, EMPTY_STRING_REPLACEMENT));
        if (!componentNode.isMissingNode()) {
            resolvedSchema = (ObjectNode) componentNode;
        }
        return resolvedSchema;
    }

    protected String getResolvedSchema(String schemaRef, ObjectNode resolvedSchemaOriginal, Map<String, JsonNode> schemaRefs) {
        ObjectNode resolvedSchema = resolvedSchemaOriginal.deepCopy();
        ObjectNode definitions = OBJECT_MAPPER.createObjectNode();
        schemaRefs.forEach(definitions::set);
        try {
            resolvedSchema.set(DEFINITIONS_NODE_NAME, OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(definitions)));
            resolvedSchema.set(SCHEMA_ID_NODE_NAME, new TextNode(SCHEMA_ID_VALUE_DOMAIN.concat(schemaRef.replace(MESSAGES_PREFIX, EMPTY_STRING_REPLACEMENT))));
            resolvedSchema.set(SCHEMA_HEADER_NODE_NAME, SCHEMA_HEADER_VALUE);
                return OBJECT_MAPPER.writeValueAsString(resolvedSchema);
        } catch (JsonProcessingException e) {
            log.error("Error during parsing components node", e);
        }

        return DEFAULT_SCHEMA_VALUE;
    }

    protected void convertPayloadToSchemaNode(ObjectNode componentNode) {
        ObjectNode payloadNode = (ObjectNode) componentNode.get(PAYLOAD_FIELD_NAME);
        if (payloadNode != null) {
            Iterator<Map.Entry<String, JsonNode>> payloadNodeIterator = payloadNode.fields();
            while (payloadNodeIterator.hasNext()) {
                Map.Entry<String, JsonNode> payloadField = payloadNodeIterator.next();
                componentNode.set(payloadField.getKey(), payloadField.getValue());
            }
            componentNode.remove(PAYLOAD_FIELD_NAME);
            componentNode.remove(HEADERS_FIELD_NAME);
        }
    }

    protected Map<String, JsonNode> getNestedRefs(ObjectNode schemaNode, JsonNode componentsNode, String modelType, List<String> refList) {
        Map<String, JsonNode> result = new TreeMap<>();
        if (refList.isEmpty()) {
            refList = new LinkedList<>();
        }

        if (schemaNode.has(TYPE_FIELD_NAME)) {
            switch (schemaNode.get(TYPE_FIELD_NAME).asText()) {
                case OBJECT_FIELD_TYPE: {
                    JsonNode propertiesNode = schemaNode.get(PROPERTIES_FIELD_NAME);
                    getSchemaNodeProperties(componentsNode, result, propertiesNode, modelType, refList);

                    JsonNode additionalPropertiesNode = schemaNode.get(ADDITIONAL_PROPERTIES_FIELD_NAME);
                    getSchemaNodeProperties(componentsNode, result, additionalPropertiesNode, modelType, refList);

                    break;
                }
                case ARRAY_FIELD_TYPE: {
                    if (schemaNode.has(ITEMS_FIELD_NAME)) {
                        JsonNode itemsNode = schemaNode.get(ITEMS_FIELD_NAME);
                        if (itemsNode.has(REF_FIELD_NAME)) {
                            String refKey = getNewRef(itemsNode.get(REF_FIELD_NAME).asText());
                            JsonNode newRefNode = new TextNode(refKey);
                            schemaNode.replace(REF_FIELD_NAME, newRefNode);
                            getSchemaNodeProperties(componentsNode, result, schemaNode, modelType, refList);
                            result.put(refKey.replace(DEFINITIONS_PREFIX, EMPTY_STRING_REPLACEMENT), schemaNode);
                        }
                    }
                    break;
                }
            }
        } else if (schemaNode.has(REF_FIELD_NAME)) {
            String refKey = getNewRef(schemaNode.get(REF_FIELD_NAME).asText());
            JsonNode newRefNode = new TextNode(refKey);
            schemaNode.replace(REF_FIELD_NAME, newRefNode);
            result.put(refKey.replace(DEFINITIONS_PREFIX, EMPTY_STRING_REPLACEMENT), schemaNode);
        }
        return result;
    }

    private void getSchemaNodeProperties(JsonNode componentsNode, Map<String, JsonNode> result, JsonNode additionalPropertiesNode, String modelType, List<String> refList) {
        if (additionalPropertiesNode != null) {
            if (additionalPropertiesNode.elements() != null) {
                additionalPropertiesNode.elements().forEachRemaining(property -> {
                    if (property.isObject()) {
                        Map<String, ObjectNode> refValues = getRefs((ObjectNode) property, componentsNode, modelType);

                        refValues.keySet().forEach(schemaRef -> {
                            if (!refList.contains(schemaRef)) {
                                refList.add(schemaRef);
                                result.put(schemaRef, refValues.get(schemaRef));
                                result.putAll(getNestedRefs(refValues.get(schemaRef), componentsNode, modelType, refList));
                            }
                        });
                    }
                });
            }
        }
    }

    private Map<String, ObjectNode> getRefs(ObjectNode property, JsonNode componentsNode, String modelType) {
        ObjectNode iterableProperties = property.has(ITEMS_FIELD_NAME) ? (ObjectNode) property.get(ITEMS_FIELD_NAME) : property;
        Map<String, ObjectNode> iterablePropertiesRefs = getIterablePropertyRefs(iterableProperties, componentsNode, modelType);
        if (iterablePropertiesRefs != null) {
            return iterablePropertiesRefs;
        }

        Map<String, ObjectNode> result = new TreeMap<>();
        String refKey = EMPTY_REF;
        String refKeyNew = EMPTY_REF;
        if (property.has(REF_FIELD_NAME)) {
            refKey = property.get(REF_FIELD_NAME).asText();
            refKey = refKey.replace(COMPONENTS_PREFIX, EMPTY_STRING_REPLACEMENT);
            refKeyNew = getNewRef(property.get(REF_FIELD_NAME).asText());
            JsonNode newRefNode = new TextNode(refKeyNew);
            property.replace(REF_FIELD_NAME, newRefNode);
        }
        if (property.has(ITEMS_FIELD_NAME)) {
            ObjectNode items = (ObjectNode) property.get(ITEMS_FIELD_NAME);
            if (items.has(REF_FIELD_NAME)) {
                refKey = items.get(REF_FIELD_NAME).asText();
                refKey = refKey.replace(COMPONENTS_PREFIX, EMPTY_STRING_REPLACEMENT);
                refKeyNew = getNewRef(items.get(REF_FIELD_NAME).asText());
                JsonNode newRefNode = new TextNode(refKeyNew);
                items.replace(REF_FIELD_NAME, newRefNode);
                property.replace(ITEMS_FIELD_NAME, items);
            }
        }
        if (!refKey.equals(refKeyNew)) {
            JsonNode componentJsonNode = componentsNode.at(refKey);
            if (componentJsonNode.isMissingNode()) {
                componentJsonNode = OBJECT_MAPPER.createObjectNode();
            }
            ObjectNode componentNode = (ObjectNode) componentJsonNode;

            if ("asyncapi".equals(modelType)) {
                convertPayloadToSchemaNode(componentNode);
            }

            result.put(refKeyNew.replace(DEFINITIONS_PREFIX, EMPTY_STRING_REPLACEMENT), componentNode);
        }
        return result;
    }

    private Map<String, ObjectNode> getIterablePropertyRefs(ObjectNode property, JsonNode componentsNode, String modelType) {
        Map<String, ObjectNode> result = new TreeMap<>();
        String fieldName = "";
        if (property.has(PROPERTIES_FIELD_NAME)) {
            fieldName = PROPERTIES_FIELD_NAME;
        }
        if (property.has(ALL_OF_FIELD_NAME)) {
            fieldName = ALL_OF_FIELD_NAME;
        }
        if (property.has(ANY_OF_FIELD_NAME)) {
            fieldName = ANY_OF_FIELD_NAME;
        }
        if (!fieldName.isEmpty()) {
            JsonNode nestedProperties = property.get(fieldName);
            Iterator<JsonNode> propertiesIterator = nestedProperties.elements();
            while (propertiesIterator.hasNext()) {
                ObjectNode nestedProperty = (ObjectNode) propertiesIterator.next();
                result.putAll(getRefs(nestedProperty, componentsNode, modelType));
            }
            return result;
        }
        return null;
    }

    private String getNewRef(String currentRef) {
        String result;
        if (currentRef.contains(SCHEMAS_PREFIX)) {
            result = currentRef.replace(SCHEMAS_PREFIX, DEFINITIONS_PREFIX);
            return result;
        }
        if (currentRef.contains(MESSAGES_PREFIX)) {
            result = currentRef.replace(MESSAGES_PREFIX, DEFINITIONS_PREFIX);
            return result;
        }
        return currentRef;
    }
}
