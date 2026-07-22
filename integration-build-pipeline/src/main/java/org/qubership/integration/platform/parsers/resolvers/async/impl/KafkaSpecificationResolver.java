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

package org.qubership.integration.platform.parsers.resolvers.async.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.parsers.model.asyncapi.Channel;
import org.qubership.integration.platform.parsers.model.asyncapi.MethodType;
import org.qubership.integration.platform.parsers.model.asyncapi.OperationObject;
import org.qubership.integration.platform.parsers.resolvers.async.AbstractAsyncApiSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncApiSchemaResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncResolver;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.integration.platform.parsers.resolvers.async.AsyncConstants.KAFKA_BINDING_CLASS;


@Slf4j
@AsyncResolver(KAFKA_BINDING_CLASS)
public class KafkaSpecificationResolver extends AbstractAsyncApiSpecificationResolver {

    public static final String PROPERTY_TOPIC = "topic";
    private static final String PROPERTY_MAAS_CLASSIFIER_NAME = "maasClassifierName";

    public KafkaSpecificationResolver(AsyncApiSchemaResolver asyncApiSchemaResolver) {
        super(asyncApiSchemaResolver);
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
        ObjectNode specificationNode = objectMapper.createObjectNode();
        specificationNode.put(PROPERTY_TOPIC, channelName);
        if (operationObject.getMaasClassifierName() != null) {
            specificationNode.put(PROPERTY_MAAS_CLASSIFIER_NAME, operationObject.getMaasClassifierName());
        }
        return specificationNode;
    }

    @Override
    public String getMethod(Channel channel, OperationObject operationObject) {
        if (operationObject.getAction() != null) {
            return operationObject.getAction();
        }
        if (channel.getPublish() != null && channel.getPublish().equals(operationObject)) {
            return MethodType.PUBLISH.getMethodName();
        }
        return MethodType.SUBSCRIBE.getMethodName();
    }
}
