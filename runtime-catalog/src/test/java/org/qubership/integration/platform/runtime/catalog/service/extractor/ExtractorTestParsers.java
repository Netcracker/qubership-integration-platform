package org.qubership.integration.platform.runtime.catalog.service.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
import org.qubership.integration.platform.parsers.asyncapi.AsyncApiV3Normalizer;
import org.qubership.integration.platform.parsers.impl.AsyncapiSpecificationParser;
import org.qubership.integration.platform.parsers.impl.GraphqlSpecificationParser;
import org.qubership.integration.platform.parsers.impl.OpenApiMapperResolver;
import org.qubership.integration.platform.parsers.impl.ProtobufSpecificationParser;
import org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser;
import org.qubership.integration.platform.parsers.resolvers.SwaggerSchemaResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncApiSchemaResolver;
import org.qubership.integration.platform.parsers.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.async.impl.AMQPSpecificationResolver;
import org.qubership.integration.platform.parsers.resolvers.async.impl.KafkaSpecificationResolver;
import org.qubership.integration.platform.parsers.schemas.SchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ArraySchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.DefaultSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.FileSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ObjectSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.StringSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.UUIDSchemaProcessor;

import java.util.List;
import java.util.stream.Stream;

/**
 * Builds the library parsers the extractor delegates to. They are plain objects with no persistence behind
 * them now, so the fixture is a constructor call rather than a mock graph.
 */
public final class ExtractorTestParsers {

    private static final int GRAPHQL_MAX_TOKENS = 1_000_000;

    private ExtractorTestParsers() {
    }

    public static OperationSchemaExtractor extractor() {
        return extractor(swaggerParser());
    }

    /** The same extractor with the HTTP parser supplied, so a test can observe what the extractor asks it for. */
    public static OperationSchemaExtractor extractor(SwaggerSpecificationParser swaggerParser) {
        return new OperationSchemaExtractor(
                swaggerParser,
                asyncapiParser(),
                graphqlParser(),
                protobufParser());
    }

    public static SwaggerSpecificationParser swaggerParser() {
        ObjectMapper mapper = new ObjectMapper();
        // The parser resolves a processor per schema class and has no fallback of its own, so the fixture
        // registers the same set the Spring context does.
        List<SchemaProcessor> leafProcessors = List.of(
                new DefaultSchemaProcessor(mapper),
                new ObjectSchemaProcessor(mapper),
                new StringSchemaProcessor(mapper),
                new UUIDSchemaProcessor(mapper),
                new FileSchemaProcessor(mapper));
        List<SchemaProcessor> processors = Stream.concat(
                leafProcessors.stream(),
                Stream.of(new ArraySchemaProcessor(leafProcessors, mapper))).toList();
        return new SwaggerSpecificationParser(new SwaggerSchemaResolver(), processors, new OpenApiMapperResolver());
    }

    public static AsyncapiSpecificationParser asyncapiParser() {
        ObjectMapper jsonMapper = new ObjectMapper();
        YAMLMapper yamlMapper = new YAMLMapper();
        List<AsyncApiSpecificationResolver> resolvers = List.of(
                new AMQPSpecificationResolver(new AsyncApiSchemaResolver()),
                new KafkaSpecificationResolver(new AsyncApiSchemaResolver()));
        return new AsyncapiSpecificationParser(
                new AsyncApiV3Normalizer(jsonMapper), jsonMapper, yamlMapper, resolvers);
    }

    public static GraphqlSpecificationParser graphqlParser() {
        ParserOptions operationParserOptions = ParserOptions.getDefaultOperationParserOptions()
                .transform(builder -> builder.maxTokens(GRAPHQL_MAX_TOKENS));
        return new GraphqlSpecificationParser(new Parser(), operationParserOptions, new ObjectMapper());
    }

    public static ProtobufSpecificationParser protobufParser() {
        return new ProtobufSpecificationParser(new ObjectMapper());
    }
}
