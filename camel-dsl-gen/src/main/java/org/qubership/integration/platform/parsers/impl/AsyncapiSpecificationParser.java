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

package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.asyncapi.AsyncApiV3Normalizer;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedOperationImpl;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;
import org.qubership.integration.platform.parsers.model.asyncapi.AsyncApiVersion;
import org.qubership.integration.platform.parsers.model.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.parsers.model.asyncapi.Channel;
import org.qubership.integration.platform.parsers.model.asyncapi.OperationObject;
import org.qubership.integration.platform.parsers.model.asyncapi.v3.AsyncapiV3Specification;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.parsers.resolvers.async.AsyncConstants.AMQP_BINDING_CLASS;

/**
 * Parses an AsyncAPI specification into a persistence-free system model.
 *
 * <p>The parser reads the source, normalizes AsyncAPI 3.0 documents onto the 2.x model, and turns
 * each channel operation into a {@link ParsedOperation} through the binding resolver for the
 * system's protocol. It performs no environment side effect: the catalog wrapper reads the parsed
 * {@link AsyncapiSpecification} and resolves the owning system's environments from its servers.
 * {@link #read} and {@link #toSystemModel} let that wrapper parse the source once and reuse the
 * specification for both operations and environment resolution.
 */
@Slf4j
public class AsyncapiSpecificationParser {

    private final AsyncApiV3Normalizer v3Normalizer;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    private final Map<String, AsyncApiSpecificationResolver> specificationResolverMap = new HashMap<>();

    public AsyncapiSpecificationParser(
            AsyncApiV3Normalizer v3Normalizer,
            ObjectMapper jsonMapper,
            ObjectMapper yamlMapper,
            List<AsyncApiSpecificationResolver> resolvers
    ) {
        this.v3Normalizer = v3Normalizer;
        this.jsonMapper = jsonMapper;
        this.yamlMapper = yamlMapper;
        for (AsyncApiSpecificationResolver resolver : resolvers) {
            AsyncResolver resolverAnnotation = resolver.getClass().getAnnotation(AsyncResolver.class);
            if (resolverAnnotation != null) {
                this.specificationResolverMap.put(resolverAnnotation.value(), resolver);
            }
        }
    }

    /**
     * Reads a specification source, normalizing an AsyncAPI 3.0 document onto the 2.x model. The
     * catalog wrapper reuses the returned specification for environment resolution.
     */
    public AsyncapiSpecification read(String data) throws JsonProcessingException {
        ObjectMapper mapper = getMapper(data);
        JsonNode rootNode = mapper.readTree(data);
        String version = rootNode.path("asyncapi").asText();
        if (AsyncApiVersion.detect(version) == AsyncApiVersion.V3) {
            AsyncapiV3Specification v3 = mapper.treeToValue(rootNode, AsyncapiV3Specification.class);
            return v3Normalizer.normalize(v3);
        }
        return mapper.treeToValue(rootNode, AsyncapiSpecification.class);
    }

    /**
     * Builds the system model from an already-read specification: the declared version and
     * description, and the operations for the given protocol's binding.
     */
    public ParsedSystemModel toSystemModel(AsyncapiSpecification importedAsyncApi, String protocol) {
        List<ParsedOperation> operations = separate(importedAsyncApi, protocol);
        return ParsedSystemModelImpl.builder()
                .version(importedAsyncApi.getInfo().getVersion())
                .description(importedAsyncApi.getInfo().getDescription())
                .operations(operations)
                .build();
    }

    private ObjectMapper getMapper(String data) {
        return data.trim().startsWith("{") ? jsonMapper : yamlMapper;
    }

    AsyncApiSpecificationResolver resolveSpecificationResolver(String protocol) {
        if (protocol == null) {
            throw unsupportedBindingException("Cannot parse AsyncAPI specification: system protocol is not set.");
        }
        AsyncApiSpecificationResolver resolver = specificationResolverMap.get(protocol);
        if (resolver == null) {
            throw unsupportedBindingException(
                    "AsyncAPI parsing is not supported for protocol '" + protocol + "'.");
        }
        return resolver;
    }

    private SpecificationParserException unsupportedBindingException(String reason) {
        String supported = specificationResolverMap.keySet().stream()
                .sorted()
                .collect(Collectors.joining(", "));
        return new SpecificationParserException(
                reason + " Supported AsyncAPI bindings: " + supported + ".");
    }

    private List<ParsedOperation> separate(AsyncapiSpecification importedAsyncApi, String protocol) {
        List<ParsedOperation> operations = new ArrayList<>();

        AsyncApiSpecificationResolver specificationResolver = resolveSpecificationResolver(protocol);

        Map<String, Channel> channels = importedAsyncApi.getChannels();
        if (channels == null || channels.isEmpty()) {
            return operations;
        }
        channels.forEach((channelName, channel) -> {
            List<OperationObject> operationObjects = specificationResolver.getOperationObjects(channel);

            for (OperationObject operationObject : operationObjects) {
                if (AMQP_BINDING_CLASS.equals(protocol)) {
                    operationObject.setOperationId(channelName);
                }

                JsonNode specification = specificationResolver.getSpecificationJsonNode(channelName, channel, operationObject);
                ParsedOperation operation = ParsedOperationImpl.builder()
                        .path(channelName)
                        .method(specificationResolver.getMethod(channel, operationObject))
                        .name(operationObject.getOperationId())
                        .specification(specification)
                        .build();
                specificationResolver.setUpOperationMessages(operation, operationObject, importedAsyncApi.getComponents());
                operations.add(operation);
            }
        });
        return operations;
    }
}
