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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.Parser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
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

@Slf4j
@Service
@Parser("asyncapi")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class AsyncapiSpecificationParser implements SpecificationParser {
    private final SystemModelRepository systemModelRepository;
    private final EnvironmentBaseService environmentBaseService;
    private final ParserUtils parserUtils;

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    private final Map<String, AsyncApiSpecificationResolver> specificationResolverMap = new HashMap<>();

    @Autowired
    public AsyncapiSpecificationParser(@Lazy EnvironmentBaseService environmentBaseService,
                                       SystemModelRepository systemModelRepository,
                                       ParserUtils parserUtils,
                                       @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper,
                                       YAMLMapper yamlExportImportMapper,
                                       List<AsyncApiSpecificationResolver> resolverList) {
        this.systemModelRepository = systemModelRepository;
        this.environmentBaseService = environmentBaseService;
        this.parserUtils = parserUtils;
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
        return mapper.readValue(data, AsyncapiSpecification.class);
    }

    @Override
    public SystemModel enrichSpecificationGroup(
            SpecificationGroup group,
            Collection<SpecificationSource> sources,
            Set<String> oldSystemModelsIds,
            boolean isDiscovered,
            Consumer<String> messageHandler
    ) {
        try {
            SystemModel systemModel;
            String specificationText = sources.stream().map(SpecificationSource::getSource).findFirst().orElse("");
            AsyncapiSpecification importedAsyncApi = read(specificationText);
            String systemModelName = parserUtils.defineVersionName(group, importedAsyncApi);
            String systemModelId = buildId(group.getId(), systemModelName);

            checkSpecId(oldSystemModelsIds, systemModelId);

            OperationProtocol operationProtocol = group.getSystem().getProtocol();
            List<Operation> operations = separate(importedAsyncApi, operationProtocol);

            environmentBaseService.resolveEnvironments(
                    importedAsyncApi,
                    operationProtocol,
                    group.getSystem(),
                    messageHandler);

            systemModel = SystemModel.builder().id(systemModelId).build();

            systemModel = systemModelRepository.save(systemModel);
            systemModel.setName(systemModelName);
            systemModel.setVersion(parserUtils.defineVersion(group, importedAsyncApi));
            systemModel.setDescription(importedAsyncApi.getInfo().getDescription());

            setOperationIds(systemModelId, operations, messageHandler.andThen(log::warn));

            operations.forEach(systemModel::addProvidedOperation);
            group.addSystemModel(systemModel);

            return systemModel;
        } catch (SpecificationSimilarIdException | SpecificationSimilarVersionException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private ObjectMapper getMapper(String data) {
        return data.trim().startsWith("{") ? jsonMapper : yamlMapper;
    }

    private List<Operation> separate(AsyncapiSpecification importedAsyncApi, OperationProtocol operationProtocol) {
        List<Operation> operations = new ArrayList<>();

        AsyncApiSpecificationResolver specificationResolver = specificationResolverMap.get(operationProtocol.getValue());

        importedAsyncApi.getChannels().forEach((channelName, channel) -> {
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

