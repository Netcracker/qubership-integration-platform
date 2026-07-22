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

package org.qubership.integration.platform.parsers.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import graphql.parser.ParserOptions;
import org.qubership.integration.platform.parsers.asyncapi.AsyncApiV3Normalizer;
import org.qubership.integration.platform.parsers.impl.AsyncapiSpecificationParser;
import org.qubership.integration.platform.parsers.impl.GraphqlSpecificationParser;
import org.qubership.integration.platform.parsers.impl.OpenApiMapperResolver;
import org.qubership.integration.platform.parsers.impl.ProtobufSpecificationParser;
import org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser;
import org.qubership.integration.platform.parsers.impl.WsdlSpecificationParser;
import org.qubership.integration.platform.parsers.resolvers.SwaggerSchemaResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncApiSchemaResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.async.impl.AMQPSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.async.impl.KafkaSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlRootFileParser;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersionParser;
import org.qubership.integration.platform.parsers.schemas.SchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ArraySchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.DefaultSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.FileSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ObjectSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.StringSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.UUIDSchemaProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.LoaderOptions;

import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;

/**
 * Wires the persistence-free specification parsers.
 *
 * <p>The parsers carry no Spring stereotype, so this auto-configuration constructs them and hands
 * each the collaborators it needs: the GraphQL parser and parser options, the JSON and OpenAPI
 * mappers, and the WSDL SAX factory. It is a library auto-configuration so a consumer of this library
 * alone gets working parsers; an application that already defines any of these beans keeps its own.
 */
@AutoConfiguration
public class SpecificationParserConfiguration {

    private static final int CODE_POINT_LIMIT_MB = 256;

    @Bean
    @ConditionalOnMissingBean(GraphqlSpecificationParser.class)
    public GraphqlSpecificationParser graphqlSpecificationParser(
            graphql.parser.Parser graphqlParser,
            @Qualifier("graphqlOperationParserOptions") ParserOptions graphqlParserOptions,
            @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper
    ) {
        return new GraphqlSpecificationParser(graphqlParser, graphqlParserOptions, jsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ProtobufSpecificationParser.class)
    public ProtobufSpecificationParser protobufSpecificationParser(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper
    ) {
        return new ProtobufSpecificationParser(objectMapper);
    }

    /**
     * Builds the library Swagger parser with the schema processors it dispatches to.
     * {@code OperationParserService} dispatches to this parser by protocol and reconciles the
     * environments it emits. The processors serialize schemas with the swagger-core OpenAPI mapper,
     * so they take {@code openApiObjectMapper} rather than the primary mapper.
     */
    @Bean
    @ConditionalOnMissingBean(SwaggerSpecificationParser.class)
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

    /**
     * Builds the library AsyncAPI parser with the binding resolvers it dispatches to.
     * {@code OperationParserService} dispatches to this parser by protocol and reconciles the
     * environments it emits. The normalizer and resolvers carry no Spring stereotype, so this
     * configuration constructs them and hands the parser the primary JSON mapper and a YAML mapper.
     */
    @Bean
    @ConditionalOnMissingBean(AsyncapiSpecificationParser.class)
    public AsyncapiSpecificationParser libraryAsyncapiSpecificationParser(
            @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper
    ) {
        AsyncApiSchemaResolver asyncApiSchemaResolver = new AsyncApiSchemaResolver();
        List<AsyncApiSpecificationResolver> resolvers = List.of(
                new AMQPSpecificationResolver(asyncApiSchemaResolver),
                new KafkaSpecificationResolver(asyncApiSchemaResolver)
        );
        return new AsyncapiSpecificationParser(
                new AsyncApiV3Normalizer(jsonMapper),
                jsonMapper,
                createAsyncapiYamlMapper(),
                resolvers);
    }

    /**
     * Builds the YAML mapper the AsyncAPI parser reads specs with. The parser deserializes a spec
     * into partial POJOs, so the mapper must ignore the many properties those POJOs do not model; the
     * large code-point limit lets it read big specs. This matches the read behavior the parser relied
     * on before this configuration moved into the library.
     */
    private static YAMLMapper createAsyncapiYamlMapper() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(CODE_POINT_LIMIT_MB * 1024 * 1024);
        YAMLFactory yamlFactory = YAMLFactory.builder().loaderOptions(loaderOptions).build();
        YAMLMapper yamlMapper = new YAMLMapper(yamlFactory);
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return yamlMapper;
    }

    /**
     * Detects the WSDL version of a source. The library WSDL parser depends on this bean to pick the
     * WSDL 1.1 or WSDL 2.0 reading path.
     */
    @Bean
    @ConditionalOnMissingBean(WsdlVersionParser.class)
    public WsdlVersionParser wsdlVersionParser(
            @Qualifier("wsdlVersionSaxParserFactory") SAXParserFactory saxParserFactory
    ) {
        return new WsdlVersionParser(saxParserFactory);
    }

    /**
     * Identifies the root WSDL among the uploaded files. The import flow depends on this bean to pick
     * the main source before parsing.
     */
    @Bean
    @ConditionalOnMissingBean(WsdlRootFileParser.class)
    public WsdlRootFileParser wsdlRootFileParser(
            @Qualifier("wsdlVersionSaxParserFactory") SAXParserFactory saxParserFactory
    ) {
        return new WsdlRootFileParser(saxParserFactory);
    }

    /**
     * Builds the library WSDL parser. {@code OperationParserService} dispatches to this parser by
     * protocol and reconciles the environments it emits.
     */
    @Bean
    @ConditionalOnMissingBean(WsdlSpecificationParser.class)
    public WsdlSpecificationParser libraryWsdlSpecificationParser(WsdlVersionParser wsdlVersionParser) {
        return new WsdlSpecificationParser(wsdlVersionParser);
    }
}
