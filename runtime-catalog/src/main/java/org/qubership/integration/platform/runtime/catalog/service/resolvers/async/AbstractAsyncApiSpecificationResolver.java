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

package org.qubership.integration.platform.runtime.catalog.service.resolvers.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.tuple.MutablePair;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Message;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.Components;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;

import java.util.*;

public abstract class AbstractAsyncApiSpecificationResolver implements AsyncApiSpecificationResolver {

    private static final String PAYLOAD_FIELD_NAME = "payload";
    private static final String MESSAGES_PREFIX = "#/components/messages/";
    private static final String REF_FIELD_NAME = "$ref";
    private static final String EMPTY_STRING_REPLACEMENT = "";
    private static final String SCHEMA_RESOLVING_ERROR = "An error occurred during schema resolving";

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final AsyncApiSchemaResolver asyncApiSchemaResolver;

    protected AbstractAsyncApiSpecificationResolver(AsyncApiSchemaResolver asyncApiSchemaResolver) {
        this.asyncApiSchemaResolver = asyncApiSchemaResolver;
    }

    @Override
    public void setUpOperationMessages(Operation operation, OperationObject operationObject, Components components) {
        operation.setRequestSchema(Collections.emptyMap());
        operation.setResponseSchemas(getMessageSchema(operationObject, components));
    }

    protected Map<String, JsonNode> getMessageSchema(OperationObject operationObject, Components components) {
        Map<String, JsonNode> messageSchema = new HashMap<>();
        if (operationObject.getMessage() != null) {
            Message message = operationObject.getMessage();
            if (message.getPayload() != null) {
                messageSchema.put(PAYLOAD_FIELD_NAME, objectMapper.valueToTree(message.getPayload()));
                return messageSchema;
            }

            JsonNode importedComponents = objectMapper.valueToTree(components);

            if (message.get$ref() != null) {
                MutablePair<String, JsonNode> refPair = getRefNode(message.get$ref(), importedComponents);
                messageSchema.put(refPair.left, refPair.right);
                return messageSchema;
            }

            if (message.getOneOf() != null) {
                return getRefsMessageNode(message.getOneOf(), importedComponents);
            }
            if (message.getAllOf() != null) {
                return getRefsMessageNode(message.getAllOf(), importedComponents);
            }
            if (message.getAnyOf() != null) {
                return getRefsMessageNode(message.getAnyOf(), importedComponents);
            }
        }

        return messageSchema;
    }

    private Map<String, JsonNode> getRefsMessageNode(List<Map<String, Object>> refs, JsonNode importedComponents) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            ObjectNode refNode = objectMapper.valueToTree(refs.get(i));

            if (refNode.has(REF_FIELD_NAME)) {
                MutablePair<String, JsonNode> pair = getRefNode(
                        refNode.get(REF_FIELD_NAME).asText(), importedComponents);
                result.put(pair.left, pair.right);
            } else if (refNode.has(PAYLOAD_FIELD_NAME)) {
                String key = PAYLOAD_FIELD_NAME;
                if (refNode.has("name")) {
                    key = refNode.get("name").asText();
                } else if (refs.size() > 1) {
                    key = PAYLOAD_FIELD_NAME + "_" + i;
                }
                result.put(key, refNode.get(PAYLOAD_FIELD_NAME));
            }
        }
        return result;
    }

    private MutablePair<String, JsonNode> getRefNode(String ref, JsonNode importedComponents) {
        try {
            String refName = ref.replace(MESSAGES_PREFIX, EMPTY_STRING_REPLACEMENT);
            String resolvedSchema = asyncApiSchemaResolver.resolveRef(ref, importedComponents);
            return new MutablePair<>(refName, objectMapper.readTree(resolvedSchema));
        } catch (JsonProcessingException e) {
            throw new SpecificationImportException(SCHEMA_RESOLVING_ERROR, e);
        }
    }
}
