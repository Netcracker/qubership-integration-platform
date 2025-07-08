package org.qubership.integration.platform.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.support.ParameterDeclarations;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.fail;

public class SchemaOnSamplesTest {
    private static final Logger logger = Logger.getLogger(SchemaOnSamplesTest.class.getName());

    static class ThisTestArgumentsProvider implements ArgumentsProvider {
        private static final YAMLMapper yamlMapper = new YAMLMapper();

        @Override
        public Stream<? extends Arguments> provideArguments(
                ParameterDeclarations parameters,
                ExtensionContext context
        ) throws Exception {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:samples/**/*.yaml");
            return Arrays.stream(resources).map(resource -> {
                try {
                    JsonNode node = yamlMapper.readTree(resource.getInputStream());
                    String schema = node.get("$schema").asText();
                    return Arguments.of(schema, resource);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static JsonSchemaFactory jsonSchemaFactory;
    private static SchemaValidatorsConfig config;

    @BeforeAll
    public static void setUp() {
        // This creates a schema factory that will use Draft-07 as the default if $schema is not specified
        // in the schema data. If $schema is specified in the schema data, then that schema dialect will be used
        // instead and this version is ignored.
        jsonSchemaFactory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V7,
                builder -> builder.schemaMappers(schemaMappers -> schemaMappers.mappings(
                    iri -> iri.startsWith("http://qubership.org/schemas/product/qip"),
                    iri -> {
                        String result = iri.replace("http://qubership.org/schemas/product/qip", "classpath:qip-model");
                        logger.info(iri + " -> " + result);
                        return result;
                    }))
        );
        SchemaValidatorsConfig.Builder builder = SchemaValidatorsConfig.builder();

        // By default, the JDK regular expression implementation which is not ECMA 262 compliant is used
        // Note that setting this requires including optional dependencies
        // builder.regularExpressionFactory(GraalJSRegularExpressionFactory.getInstance());
        // builder.regularExpressionFactory(JoniRegularExpressionFactory.getInstance());
        config = builder.build();
    }

    @ParameterizedTest
    @ArgumentsSource(ThisTestArgumentsProvider.class)
    public void testSchemaOnSample(String schemaId, Resource resource) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        JsonSchema schema = jsonSchemaFactory.getSchema(SchemaLocation.of(schemaId), config);
        Set<ValidationMessage> assertions = schema.validate(source, InputFormat.YAML, executionContext -> {
            executionContext.getExecutionConfig().setFormatAssertionsEnabled(true);
        });
        assertAll(schemaId + " -> " + resource.getURI().getPath(), assertions.stream().map(
                validationMessage -> () -> fail(validationMessage.getMessage())));
    }
}
