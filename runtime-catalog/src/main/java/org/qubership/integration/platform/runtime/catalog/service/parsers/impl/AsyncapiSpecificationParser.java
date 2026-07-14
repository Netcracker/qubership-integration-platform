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

package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncApiVersion;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3.AsyncapiV3Specification;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam;
import org.qubership.integration.platform.runtime.catalog.service.parsers.Parser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.asyncapi.AsyncApiV3Normalizer;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@Parser("asyncapi")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class AsyncapiSpecificationParser implements SpecificationParser {
    private final EnvironmentBaseService environmentBaseService;
    private final AsyncApiV3Normalizer v3Normalizer;

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    private final Map<String, AsyncApiSpecificationResolver> specificationResolverMap = new HashMap<>();

    @Autowired
    public AsyncapiSpecificationParser(@Lazy EnvironmentBaseService environmentBaseService,
                                       AsyncApiV3Normalizer v3Normalizer,
                                       @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper,
                                       YAMLMapper yamlExportImportMapper,
                                       List<AsyncApiSpecificationResolver> resolverList) {
        this.environmentBaseService = environmentBaseService;
        this.v3Normalizer = v3Normalizer;
        this.jsonMapper = jsonMapper;
        this.yamlMapper = yamlExportImportMapper;
        for (AsyncApiSpecificationResolver specificationResolvers : resolverList) {
            AsyncResolver resolverAnnotation = specificationResolvers.getClass().getAnnotation(AsyncResolver.class);
            if (resolverAnnotation != null) {
                this.specificationResolverMap.put(resolverAnnotation.value(), specificationResolvers);
            }
        }
    }

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

    @Override
    public ParsedSystemModel parseSpecification(
            SpecificationGroup group,
            Collection<SpecificationSource> sources,
            Consumer<String> messageHandler
    ) {
        try {
            String specificationText = sources.stream().map(SpecificationSource::getSource).findFirst().orElse("");
            AsyncapiSpecification importedAsyncApi = read(specificationText);

            OperationProtocol operationProtocol = group.getSystem().getProtocol();
            List<Operation> operations = separate(importedAsyncApi, operationProtocol);

            environmentBaseService.resolveEnvironments(
                    importedAsyncApi,
                    operationProtocol,
                    group.getSystem(),
                    messageHandler);

            List<ParsedOperation> parsedOperations = operations.stream()
                    .map(SystemEntitySeam::toParsedOperation)
                    .collect(Collectors.toList());

            return ParsedSystemModelImpl.builder()
                    .version(importedAsyncApi.getInfo().getVersion())
                    .description(importedAsyncApi.getInfo().getDescription())
                    .operations(parsedOperations)
                    .build();
        } catch (SpecificationImportException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private ObjectMapper getMapper(String data) {
        return data.trim().startsWith("{") ? jsonMapper : yamlMapper;
    }

    AsyncApiSpecificationResolver resolveSpecificationResolver(OperationProtocol operationProtocol) {
        if (operationProtocol == null) {
            throw unsupportedBindingException("Cannot parse AsyncAPI specification: system protocol is not set.");
        }
        AsyncApiSpecificationResolver resolver = specificationResolverMap.get(operationProtocol.getValue());
        if (resolver == null) {
            throw unsupportedBindingException(
                    "AsyncAPI parsing is not supported for protocol '" + operationProtocol.getValue() + "'.");
        }
        return resolver;
    }

    private SpecificationImportException unsupportedBindingException(String reason) {
        String supported = specificationResolverMap.keySet().stream()
                .sorted()
                .collect(Collectors.joining(", "));
        return new SpecificationImportException(
                reason + " Supported AsyncAPI bindings: " + supported + ".");
    }

    private List<Operation> separate(AsyncapiSpecification importedAsyncApi, OperationProtocol operationProtocol) {
        List<Operation> operations = new ArrayList<>();

        AsyncApiSpecificationResolver specificationResolver = resolveSpecificationResolver(operationProtocol);

        Map<String, Channel> channels = importedAsyncApi.getChannels();
        if (channels == null || channels.isEmpty()) {
            return operations;
        }
        channels.forEach((channelName, channel) -> {
            List<OperationObject> operationObjects = specificationResolver.getOperationObjects(channel);

            for (OperationObject operationObject : operationObjects) {
                if (operationProtocol.equals(OperationProtocol.AMQP)) {
                    operationObject.setOperationId(channelName);
                }

                JsonNode specification = specificationResolver.getSpecificationJsonNode(channelName, channel, operationObject);
                Operation operation = Operation.builder()
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

