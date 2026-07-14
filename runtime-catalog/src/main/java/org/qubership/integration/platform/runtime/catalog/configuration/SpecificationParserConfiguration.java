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
import org.qubership.integration.platform.parsers.impl.OpenApiMapperResolver;
import org.qubership.integration.platform.parsers.impl.ProtobufSpecificationParser;
import org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser;
import org.qubership.integration.platform.parsers.resolvers.SwaggerSchemaResolver;
import org.qubership.integration.platform.parsers.schemas.SchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ArraySchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.DefaultSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.FileSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ObjectSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.StringSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.UUIDSchemaProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Builds the library Swagger parser with the schema processors it dispatches to. The catalog
     * Swagger parser depends on this bean and adds the environment side effect the library omits.
     * The processors serialize schemas with the swagger-core OpenAPI mapper, so they take
     * {@code openApiObjectMapper} rather than the primary mapper.
     */
    @Bean
    public SwaggerSpecificationParser librarySwaggerSpecificationParser(
            @Qualifier("openApiObjectMapper") ObjectMapper openApiObjectMapper
    ) {
        List<SchemaProcessor> leafProcessors = List.of(
                new DefaultSchemaProcessor(openApiObjectMapper),
                new ObjectSchemaProcessor(openApiObjectMapper),
                new StringSchemaProcessor(openApiObjectMapper),
                new UUIDSchemaProcessor(openApiObjectMapper),
                new FileSchemaProcessor(openApiObjectMapper)
        );
        List<SchemaProcessor> schemaProcessors = new ArrayList<>(leafProcessors);
        schemaProcessors.add(new ArraySchemaProcessor(leafProcessors, openApiObjectMapper));

        return new SwaggerSpecificationParser(
                new SwaggerSchemaResolver(),
                schemaProcessors,
                new OpenApiMapperResolver());
    }
}
