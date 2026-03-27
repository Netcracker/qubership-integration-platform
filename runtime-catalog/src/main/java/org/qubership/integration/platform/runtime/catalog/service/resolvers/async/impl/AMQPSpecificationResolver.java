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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.MethodType;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.Components;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncConstants.AMQP_BINDING_CLASS;
import static org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncConstants.CONVERTING_OPERATION_TO_JSON_ERROR;


@Service
@AsyncResolver(AMQP_BINDING_CLASS)
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class AMQPSpecificationResolver implements AsyncApiSpecificationResolver {

    private static final String DEFAULT_SUMMARY = "AMQP operation";
    private static final String PROPERTY_USERNAME = "username";
    private static final String PROPERTY_QUEUE_NAME = "queue";
    private static final String PROPERTY_EXCHANGE_NAME = "exchangeName";
    private static final String SPECIFICATION_USER_ID = "userId";
    private static final String SPECIFICATION_NAME = "name";
    private static final String SPECIFICATION_QUEUE = "queue";
    private static final String SPECIFICATION_EXCHANGE = "exchange";
    private static final String SPECIFICATION_AMQP = "amqp";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<OperationObject> getOperationObjects(Channel channel) {
        OperationObject operationObject = new OperationObject();
        operationObject.setSummary(DEFAULT_SUMMARY);
        return Collections.singletonList(operationObject);
    }

    @Override
    public JsonNode getSpecificationJsonNode(String channelName, Channel channel, OperationObject operationObject) {
        ObjectNode specificationNode = objectMapper.createObjectNode();
        try {
            JsonNode allBindings = objectMapper.readTree(objectMapper.writeValueAsString(channel.getBindings()));
            JsonNode amqpBindings = allBindings.path(SPECIFICATION_AMQP);
            if (!amqpBindings.isMissingNode() && !amqpBindings.isNull()) {
                addIfNotNull(specificationNode, PROPERTY_USERNAME, amqpBindings.get(SPECIFICATION_USER_ID));
                addIfNotNull(specificationNode, PROPERTY_QUEUE_NAME, amqpBindings.at("/" + SPECIFICATION_QUEUE + "/" + SPECIFICATION_NAME));
                addIfNotNull(specificationNode, PROPERTY_EXCHANGE_NAME, amqpBindings.at("/" + SPECIFICATION_EXCHANGE + "/" + SPECIFICATION_NAME));
            }
            return specificationNode;
        } catch (JsonProcessingException e) {
            throw new SpecificationImportException(CONVERTING_OPERATION_TO_JSON_ERROR, e);
        }
    }

    @Override
    public String getMethod(Channel channel, OperationObject operationObject) {
        if (channel.getPublish() != null && channel.getPublish().equals(operationObject)) {
            return MethodType.SUBSCRIBE.getMethodName();
        }
        return MethodType.PUBLISH.getMethodName();
    }

    @Override
    public void setUpOperationMessages(Operation operation, OperationObject operationObject, Components components) {

    }

    private void addIfNotNull(ObjectNode node, String key, JsonNode value) {
        if (value != null && !value.isMissingNode() && !value.isNull()) {
            node.set(key, value);
        }
    }
}
