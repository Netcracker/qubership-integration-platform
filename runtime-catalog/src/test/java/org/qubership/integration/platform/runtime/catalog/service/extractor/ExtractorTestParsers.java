package org.qubership.integration.platform.runtime.catalog.service.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
import io.swagger.v3.core.util.Json;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.OpenApiMapperResolver;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.parsers.asyncapi.AsyncApiV3Normalizer;
import org.qubership.integration.platform.runtime.catalog.service.parsers.impl.AsyncapiSpecificationParser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.impl.GraphqlSpecificationParser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.impl.ProtobufSpecificationParser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.impl.SwaggerSpecificationParser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.preprocessing.SpecificationPreprocessing;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSchemaResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl.AMQPSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl.KafkaSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.swagger.SwaggerSchemaResolver;
import org.qubership.integration.platform.runtime.catalog.service.schemas.SchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.ArraySchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.DefaultSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.FileSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.ObjectSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.StringSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.UUIDSchemaProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;

/**
 * Wiring for extractor tests. Builds the protocol parsers the {@link OperationSchemaExtractor}
 * delegates to, backed by the real schema processors and resolvers; the persistence and version
 * collaborators are mocked because the {@code parseOperations} cores the extractor calls never
 * touch them.
 */
public final class ExtractorTestParsers {

    private static final int GRAPHQL_MAX_TOKENS = 1_000_000;

    private ExtractorTestParsers() {
    }

    /** A fully wired extractor covering every protocol the parity gate exercises. */
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
        SwaggerDependencies dependencies = swaggerDependencies();
        return new SwaggerSpecificationParser(
                dependencies.repository(),
                dependencies.schemaResolver(),
                dependencies.schemaProcessors(),
                dependencies.mapperResolver(),
                dependencies.parserUtils(),
                dependencies.environmentBaseService()
        );
    }

    /** The HTTP parser wrapped so a test can read back the {@code withSchemas} flag every call carried. */
    public static RecordingSwaggerParser recordingSwaggerParser() {
        SwaggerDependencies dependencies = swaggerDependencies();
        return new RecordingSwaggerParser(
                dependencies.repository(),
                dependencies.schemaResolver(),
                dependencies.schemaProcessors(),
                dependencies.mapperResolver(),
                dependencies.parserUtils(),
                dependencies.environmentBaseService()
        );
    }

    private record SwaggerDependencies(
            SystemModelRepository repository,
            SwaggerSchemaResolver schemaResolver,
            List<SchemaProcessor> schemaProcessors,
            OpenApiMapperResolver mapperResolver,
            ParserUtils parserUtils,
            EnvironmentBaseService environmentBaseService
    ) {
    }

    private static SwaggerDependencies swaggerDependencies() {
        ObjectMapper mapper = Json.mapper();

        List<SchemaProcessor> leafProcessors = List.of(
                new DefaultSchemaProcessor(mapper),
                new ObjectSchemaProcessor(mapper),
                new StringSchemaProcessor(mapper),
                new UUIDSchemaProcessor(mapper),
                new FileSchemaProcessor(mapper)
        );
        ArraySchemaProcessor arrayProcessor = new ArraySchemaProcessor(leafProcessors, mapper);
        List<SchemaProcessor> allProcessors = new ArrayList<>(leafProcessors);
        allProcessors.add(arrayProcessor);

        return new SwaggerDependencies(
                mock(SystemModelRepository.class),
                new SwaggerSchemaResolver(),
                allProcessors,
                new OpenApiMapperResolver(),
                mock(ParserUtils.class),
                mock(EnvironmentBaseService.class)
        );
    }

    /**
     * Records the {@code withSchemas} flag of every {@code parseOperations} call and otherwise parses for real.
     * Materializing request and response schemas is the expensive half of a parse, so a caller that reads only the
     * specification slice has to ask for {@code false}.
     */
    public static class RecordingSwaggerParser extends SwaggerSpecificationParser {

        private final List<Boolean> withSchemasCalls = new ArrayList<>();

        public RecordingSwaggerParser(
                SystemModelRepository systemModelRepository,
                SwaggerSchemaResolver swaggerSchemaResolver,
                List<SchemaProcessor> schemaProcessors,
                OpenApiMapperResolver openApiMapperResolver,
                ParserUtils parserUtils,
                EnvironmentBaseService environmentBaseService
        ) {
            super(systemModelRepository, swaggerSchemaResolver, schemaProcessors, openApiMapperResolver,
                    parserUtils, environmentBaseService);
        }

        @Override
        public List<Operation> parseOperations(
                String specificationText, boolean withSchemas, Consumer<String> messageHandler) {
            withSchemasCalls.add(withSchemas);
            return super.parseOperations(specificationText, withSchemas, messageHandler);
        }

        public List<Boolean> withSchemasCalls() {
            return withSchemasCalls;
        }
    }

    public static AsyncapiSpecificationParser asyncapiParser() {
        ObjectMapper jsonMapper = new ObjectMapper();
        YAMLMapper yamlMapper = new YAMLMapper();
        AsyncApiV3Normalizer normalizer = new AsyncApiV3Normalizer(jsonMapper);
        SpecificationPreprocessing preprocessing = new SpecificationPreprocessing(normalizer, jsonMapper, yamlMapper);
        List<AsyncApiSpecificationResolver> resolvers = List.of(
                new AMQPSpecificationResolver(new AsyncApiSchemaResolver()),
                new KafkaSpecificationResolver(new AsyncApiSchemaResolver())
        );
        return new AsyncapiSpecificationParser(
                mock(EnvironmentBaseService.class),
                mock(SystemModelRepository.class),
                mock(ParserUtils.class),
                preprocessing,
                resolvers
        );
    }

    public static GraphqlSpecificationParser graphqlParser() {
        ParserOptions operationParserOptions = ParserOptions.getDefaultOperationParserOptions()
                .transform(builder -> builder.maxTokens(GRAPHQL_MAX_TOKENS));
        return new GraphqlSpecificationParser(
                mock(SystemModelRepository.class),
                mock(ParserUtils.class),
                new Parser(),
                operationParserOptions,
                new ObjectMapper()
        );
    }

    public static ProtobufSpecificationParser protobufParser() {
        return new ProtobufSpecificationParser(
                mock(SystemModelRepository.class),
                mock(ParserUtils.class),
                new ObjectMapper()
        );
    }
}
