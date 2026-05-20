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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.language.AstPrinter;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.ObjectTypeDefinition;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@org.qubership.integration.platform.runtime.catalog.service.parsers.Parser("graphqlschema")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class GraphqlSpecificationParser implements SpecificationParser {
    public static final String MUTATION_NAME = "mutation";
    public static final String QUERY_NAME = "query";
    public static final String OPERATION_IN_SPEC_KEY = "operation";

    private final SystemModelRepository systemModelRepository;
    private final ParserUtils parserUtils;
    private final Parser graphqlParser;
    private final ParserOptions graphqlParserOptions;
    private final ObjectMapper jsonMapper;

    @Autowired
    public GraphqlSpecificationParser(SystemModelRepository systemModelRepository,
                                      ParserUtils parserUtils,
                                      Parser graphqlParser,
                                      @Qualifier("graphqlOperationParserOptions") ParserOptions graphqlParserOptions,
                                      @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper) {
        this.systemModelRepository = systemModelRepository;
        this.parserUtils = parserUtils;
        this.graphqlParser = graphqlParser;
        this.graphqlParserOptions = graphqlParserOptions;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public SystemModel enrichSpecificationGroup(
            SpecificationGroup group,
            Collection<SpecificationSource> sources,
            Set<String> oldSystemModelsIds, boolean isDiscovered,
            Consumer<String> messageHandler
    ) {
        try {
            SystemModel systemModel;
            String specificationText = sources.stream().map(SpecificationSource::getSource).findFirst().orElse("");
            List<Operation> operationList = getParsedOperations(specificationText);
            String systemModelName = parserUtils.defineVersionName(group, group);
            String systemModelId = buildId(group.getId(), systemModelName);

            checkSpecId(oldSystemModelsIds, systemModelId);

            systemModel = SystemModel.builder().id(systemModelId).build();

            systemModel = systemModelRepository.save(systemModel);
            systemModel.setName(systemModelName);
            systemModel.setVersion(parserUtils.defineVersion(group, group));

            setOperationIds(systemModelId, operationList, messageHandler.andThen(log::warn));

            operationList.forEach(systemModel::addProvidedOperation);
            group.addSystemModel(systemModel);

            return systemModel;
        } catch (SpecificationSimilarIdException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private List<Operation> getParsedOperations(String specificationInput) {
        ParserEnvironment parserEnvironment = ParserEnvironment.newParserEnvironment().document(specificationInput).parserOptions(graphqlParserOptions).build();
        Document document = graphqlParser.parseDocument(parserEnvironment);
        List<Operation> operations = new ArrayList<>();

        for (Definition definition : document.getDefinitions()) {
            if (definition instanceof ObjectTypeDefinition) {
                ObjectTypeDefinition objectTypeDefinition = (ObjectTypeDefinition) definition;
                switch (objectTypeDefinition.getName().toLowerCase()) {
                    case QUERY_NAME:
                        operations.addAll(parseObjectTypeOperations(objectTypeDefinition, QUERY_NAME));
                        break;
                    case MUTATION_NAME:
                        operations.addAll(parseObjectTypeOperations(objectTypeDefinition, MUTATION_NAME));
                        break;
                }
            }
        }

        return operations;
    }

    private List<Operation> parseObjectTypeOperations(ObjectTypeDefinition objectTypeDefinition, String method) {
        return objectTypeDefinition.getFieldDefinitions().stream()
                .map(field -> {
                    String operationDefString = AstPrinter.printAst(field);
                    return Operation.builder()
                            .name(field.getName())
                            .method(method)
                            .path(operationDefString)
                            .specification(
                                    jsonMapper.convertValue(
                                            // TODO convert with comments, if possible
                                            Map.of(OPERATION_IN_SPEC_KEY, operationDefString),
                                            JsonNode.class))
                            .build();
                })
                .collect(Collectors.toList());
    }
}

