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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.language.AstPrinter;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.ObjectTypeDefinition;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParser;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedOperationImpl;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Parser("graphqlschema")
public class GraphqlSpecificationParser implements SpecificationParser {
    public static final String MUTATION_NAME = "mutation";
    public static final String QUERY_NAME = "query";
    public static final String OPERATION_IN_SPEC_KEY = "operation";

    private final graphql.parser.Parser graphqlParser;
    private final ParserOptions graphqlParserOptions;
    private final ObjectMapper jsonMapper;

    public GraphqlSpecificationParser(graphql.parser.Parser graphqlParser,
                                      ParserOptions graphqlParserOptions,
                                      ObjectMapper jsonMapper) {
        this.graphqlParser = graphqlParser;
        this.graphqlParserOptions = graphqlParserOptions;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ParsedSystemModel parseSpecification(
            String groupId,
            Collection<SpecificationSource> sources,
            Consumer<String> messageHandler
    ) {
        try {
            String specificationText = sources.stream().map(SpecificationSource::getSource).findFirst().orElse("");
            List<ParsedOperation> operationList = getParsedOperations(specificationText);
            return ParsedSystemModelImpl.builder()
                    .operations(operationList)
                    .build();
        } catch (Exception e) {
            throw new SpecificationParserException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private List<ParsedOperation> getParsedOperations(String specificationInput) {
        ParserEnvironment parserEnvironment = ParserEnvironment.newParserEnvironment().document(specificationInput).parserOptions(graphqlParserOptions).build();
        Document document = graphqlParser.parseDocument(parserEnvironment);
        List<ParsedOperation> operations = new ArrayList<>();

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

    private List<ParsedOperation> parseObjectTypeOperations(ObjectTypeDefinition objectTypeDefinition, String method) {
        return objectTypeDefinition.getFieldDefinitions().stream()
                .map(field -> {
                    String operationDefString = AstPrinter.printAst(field);
                    return (ParsedOperation) ParsedOperationImpl.builder()
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
