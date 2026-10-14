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
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParser;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.asyncapi.AsyncApiV3Normalizer;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.parsers.model.ParsedEnvironmentImpl;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedOperationImpl;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;
import org.qubership.integration.platform.parsers.model.asyncapi.AsyncApiVersion;
import org.qubership.integration.platform.parsers.model.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.parsers.model.asyncapi.Channel;
import org.qubership.integration.platform.parsers.model.asyncapi.OperationObject;
import org.qubership.integration.platform.parsers.model.asyncapi.Server;
import org.qubership.integration.platform.parsers.model.asyncapi.v3.AsyncapiV3Specification;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.parsers.resolvers.async.AsyncConstants.AMQP_BINDING_CLASS;

/**
 * Parses an AsyncAPI specification into a persistence-free system model.
 *
 * <p>The parser reads the source, normalizes AsyncAPI 3.0 documents onto the 2.x model, and turns
 * each channel operation into a {@link ParsedOperation} through the binding resolver for the
 * specification's protocol. It also maps each declared server to a {@link ParsedEnvironment} on the
 * system model, leaving the persistence side effect to the catalog: the catalog reconciles those
 * environments against the owning system. {@link #read} and {@link #toSystemModel} also stand on
 * their own for callers that already hold the protocol.
 */
@Slf4j
@Parser("asyncapi")
public class AsyncapiSpecificationParser implements SpecificationParser {

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
     * Parses the first source into a system model, deriving the protocol from the specification's
     * servers. The declared servers become the model's environments; the caller reconciles them
     * against the owning system.
     *
     * @param groupId the id of the specification group the model will belong to; unused here
     * @param sources the specification sources to parse; only the first is read
     * @param messageHandler receives human-readable warnings raised while parsing; unused here
     * @return the parsed system model
     * @throws SpecificationParserException if the source cannot be parsed
     */
    @Override
    public ParsedSystemModel parseSpecification(
            String groupId,
            Collection<SpecificationSource> sources,
            Consumer<String> messageHandler
    ) {
        try {
            String specificationText = sources.stream()
                    .map(SpecificationSource::getSource)
                    .findFirst()
                    .orElse("");
            AsyncapiSpecification importedAsyncApi = read(specificationText);
            return toSystemModel(importedAsyncApi, resolveProtocol(importedAsyncApi));
        } catch (SpecificationParserException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecificationParserException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    /**
     * Derives the binding protocol from the specification the same way the catalog does when it
     * assigns the system's protocol: the first declared server's protocol, then the {@code
     * x-protocol} field on {@code info}. Returns {@code null} when neither is present, which lets
     * {@link #resolveSpecificationResolver} raise the standard "protocol is not set" error.
     */
    private String resolveProtocol(AsyncapiSpecification specification) {
        Map<String, Server> servers = specification.getServers();
        if (servers != null) {
            for (Server server : servers.values()) {
                if (server != null && server.getProtocol() != null) {
                    return server.getProtocol();
                }
            }
        }
        return specification.getInfo() == null ? null : specification.getInfo().getProtocol();
    }

    /**
     * Reads a specification source, normalizing an AsyncAPI 3.0 document onto the 2.x model.
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
     * description, the operations for the given protocol's binding, and one environment per declared
     * server.
     */
    public ParsedSystemModel toSystemModel(AsyncapiSpecification importedAsyncApi, String protocol) {
        List<ParsedOperation> operations = separate(importedAsyncApi, protocol);
        return ParsedSystemModelImpl.builder()
                .version(importedAsyncApi.getInfo().getVersion())
                .description(importedAsyncApi.getInfo().getDescription())
                .operations(operations)
                .environments(toParsedEnvironments(importedAsyncApi.getServers()))
                .build();
    }

    private List<ParsedEnvironment> toParsedEnvironments(Map<String, Server> servers) {
        if (servers == null) {
            return new ArrayList<>();
        }
        return servers.entrySet().stream()
                .map(entry -> (ParsedEnvironment) ParsedEnvironmentImpl.builder()
                        .name(entry.getKey())
                        .address(entry.getValue().getUrl())
                        .build())
                .collect(Collectors.toList());
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
