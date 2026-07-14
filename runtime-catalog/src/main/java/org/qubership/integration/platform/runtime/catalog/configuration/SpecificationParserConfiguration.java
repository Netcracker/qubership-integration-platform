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

package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.ParserOptions;
import org.qubership.integration.platform.parsers.impl.GraphqlSpecificationParser;
import org.qubership.integration.platform.parsers.impl.ProtobufSpecificationParser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the persistence-free specification parsers relocated to the translation library.
 *
 * <p>The parsers themselves carry no Spring stereotype; the catalog owns the beans they depend on,
 * so it constructs them here and hands them the graphql parser, the parser options, and the shared
 * object mapper.
 */
@Configuration
public class SpecificationParserConfiguration {

    @Bean
    public GraphqlSpecificationParser graphqlSpecificationParser(
            graphql.parser.Parser graphqlParser,
            @Qualifier("graphqlOperationParserOptions") ParserOptions graphqlParserOptions,
            @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper
    ) {
        return new GraphqlSpecificationParser(graphqlParser, graphqlParserOptions, jsonMapper);
    }

    @Bean
    public ProtobufSpecificationParser protobufSpecificationParser(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper
    ) {
        return new ProtobufSpecificationParser(objectMapper);
    }
}
