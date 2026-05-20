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

package org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.commons.lang3.tuple.MutablePair;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Message;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.MethodType;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.Components;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.SchemaObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSchemaResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncConstants.KAFKA_BINDING_CLASS;


@Service
@AsyncResolver(KAFKA_BINDING_CLASS)
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class KafkaSpecificationResolver implements AsyncApiSpecificationResolver {


    private static final String PAYLOAD_FIELD_NAME = "payload";
    private static final String TYPE_FIELD_NAME = "type";
    private static final String PROPERTIES_FIELD_NAME = "properties";
    private static final String MESSAGES_PREFIX = "#/components/messages/";
    public static final String PROPERTY_TOPIC = "topic";
    private static final String PROPERTY_MAAS_CLASSIFIER_NAME = "maasClassifierName";
    private static final String REF_FIELD_NAME = "$ref";
    private static final String EMPTY_STRING_REPLACEMENT = "";
    private static final String SCHEMA_RESOLVING_ERROR = "An error occurred during schema resolving";
    private static final String COMPONENTS_CONVERTING_ERROR = "An error occurred during components converting";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AsyncApiSchemaResolver asyncApiSchemaResolver;

    @Autowired
    public KafkaSpecificationResolver(AsyncApiSchemaResolver asyncApiSchemaResolver) {
        this.asyncApiSchemaResolver = asyncApiSchemaResolver;
    }

    @Override
    public List<OperationObject> getOperationObjects(Channel channel) {
        List<OperationObject> operationObjects = new ArrayList<>();

        if (channel.getPublish() != null) {
            operationObjects.add(channel.getPublish());
        }
        if (channel.getSubscribe() != null) {
            operationObjects.add(channel.getSubscribe());
        }

        return operationObjects;
    }

    @Override
    public JsonNode getSpecificationJsonNode(String channelName, Channel channel, OperationObject operationObject) {
        ObjectNode specificationNode;
        specificationNode = objectMapper.createObjectNode();
        specificationNode.put(PROPERTY_TOPIC, channelName);
        if (operationObject.getMaasClassifierName() != null) {
            specificationNode.put(PROPERTY_MAAS_CLASSIFIER_NAME, operationObject.getMaasClassifierName());
        }
        return specificationNode;
    }

    @Override
    public String getMethod(Channel channel, OperationObject operationObject) {
        if (channel.getPublish() != null && channel.getPublish().equals(operationObject)) {
            return MethodType.PUBLISH.getMethodName();
        }
        return MethodType.SUBSCRIBE.getMethodName();
    }

    @Override
    public void setUpOperationMessages(Operation operation, OperationObject operationObject, Components components) {
        operation.setRequestSchema(Collections.EMPTY_MAP);
        operation.setResponseSchemas(getMessageSchema(operationObject, components));
    }

    private Map<String, JsonNode> getMessageSchema(OperationObject operationObject, Components components) {
        Map<String, JsonNode> messageSchema = new HashMap<>();
        if (operationObject.getMessage() != null) {
            Message message = operationObject.getMessage();
            if (message.getPayload() != null) {
                ObjectNode payloadMessageNode = getPayloadMessageNode(message.getPayload());
                messageSchema.put(PAYLOAD_FIELD_NAME, payloadMessageNode);
                return messageSchema;
            }

            JsonNode importedComponents;
            try {
                importedComponents = objectMapper.readTree(objectMapper.writeValueAsString(components));
            } catch (JsonProcessingException e) {
                throw new SpecificationImportException(COMPONENTS_CONVERTING_ERROR, e);
            }

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

    private ObjectNode getPayloadMessageNode(SchemaObject payload) {
        ObjectNode payloadSchemaNode = objectMapper.createObjectNode();
        payloadSchemaNode.set(TYPE_FIELD_NAME, new TextNode(payload.getType()));
        ObjectNode schemaPropertiesNode = objectMapper.createObjectNode();

        payload.getProperties().forEach((propertyName, propertyValue) -> {
            try {
                ObjectNode payloadPropertyNode = (ObjectNode) objectMapper.readTree(
                        objectMapper.writeValueAsString(propertyValue));
                ObjectNode propertyNode = objectMapper.createObjectNode();

                propertyNode.set(TYPE_FIELD_NAME,
                        new TextNode(payloadPropertyNode.get(TYPE_FIELD_NAME)
                                .asText("string")));
                schemaPropertiesNode.set(propertyName, propertyNode);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }

        });
        payloadSchemaNode.set(PROPERTIES_FIELD_NAME, schemaPropertiesNode);

        return payloadSchemaNode;
    }

    private Map<String, JsonNode> getRefsMessageNode(List<Map<String, Object>> refs, JsonNode importedComponents) {
        return refs
                .stream()
                .map(refsElem -> {
                    ObjectNode refNode;
                    try {
                        refNode = (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(refsElem));
                        return getRefNode(refNode.get(REF_FIELD_NAME).asText(), importedComponents);
                    } catch (JsonProcessingException e) {
                        throw new SpecificationImportException(SCHEMA_RESOLVING_ERROR);
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
