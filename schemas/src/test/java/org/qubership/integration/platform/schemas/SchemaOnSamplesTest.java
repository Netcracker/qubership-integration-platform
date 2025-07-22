package org.qubership.integration.platform.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.fail;

public final class SchemaOnSamplesTest {
    private static final Logger LOGGER =
            Logger.getLogger(SchemaOnSamplesTest.class.getName());

    static class ThisTestArgumentsProvider implements ArgumentsProvider {
        private static final YAMLMapper MAPPER;
        private static final Pattern SCHEMA_COMMENT_PATTERN =
                Pattern.compile("^#\\s*\\$schema:\\s+");
        private static final String SHOULD_FAIL_SUFFIX = "__SHOULD_FAIL.yaml";

        static {
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setCodePointLimit(50 * 1024 * 1024); // 25 MB
            MAPPER = new YAMLMapper(YAMLFactory.builder()
                    .loaderOptions(loaderOptions)
            .build());
        }

        @Override
        public Stream<? extends Arguments> provideArguments(
                final ParameterDeclarations parameters,
                final ExtensionContext context
        ) throws Exception {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            Resource[] resources =
                    resolver.getResources("classpath:samples/**/*.yaml");
            return Arrays.stream(resources).map(resource -> {
                try {
                    String schema = getSchema(resource);
                    boolean shouldFail = getIsTestShouldFail(resource);
                    LOGGER.log(Level.FINE, "Schema: " + schema);
                    return Arguments.of(schema, resource, shouldFail);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        }

        private String getSchema(final Resource resource) throws IOException {
            String data = resource.getContentAsString(Charset.defaultCharset());
            JsonNode node = MAPPER.readTree(data);
            return node.has("$schema")
                    ? node.get("$schema").asText()
                    : data.lines().findFirst()
                    .map(String::trim)
                    .filter(SCHEMA_COMMENT_PATTERN.asPredicate())
                    .map(line -> line.replaceAll(
                            SCHEMA_COMMENT_PATTERN.pattern(),
                            ""))
                    .orElse("");
        }

        private boolean getIsTestShouldFail(
                final Resource resource
        ) throws IOException {
            return Objects.requireNonNull(resource.getFilename())
                    .endsWith(SHOULD_FAIL_SUFFIX);
        }
    }

    private static JsonSchemaFactory jsonSchemaFactory;
    private static SchemaValidatorsConfig config;

    @BeforeAll
    public static void setUp() {
        // This creates a schema factory that will use Draft-07 as the default
        // if $schema is not specified in the schema data.
        // If $schema is specified in the schema data, then that schema dialect
        // will be used instead and this version is ignored.
        jsonSchemaFactory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V7,
                builder -> builder.schemaMappers(schemaMappers -> schemaMappers.mappings(
                        iri -> iri.startsWith("http://qubership.org/schemas/product/qip"),
                        iri -> {
                            String result = iri.replace("http://qubership.org/schemas/product/qip", "classpath:qip-model");
                            LOGGER.info(iri + " -> " + result);
                            return result;
                        })).yamlMapper(ThisTestArgumentsProvider.MAPPER)
        );
        SchemaValidatorsConfig.Builder builder = SchemaValidatorsConfig.builder();

        // By default, the JDK regular expression implementation
        // which is not ECMA 262 compliant is used.
        // Note that setting this requires including optional dependencies
        // builder.regularExpressionFactory(GraalJSRegularExpressionFactory.getInstance());
        // builder.regularExpressionFactory(JoniRegularExpressionFactory.getInstance());
        config = builder.failFast(true).losslessNarrowing(true).build();
    }

    @ParameterizedTest
    @ArgumentsSource(ThisTestArgumentsProvider.class)
    public void testSchemaOnSample(
            final String schemaId,
            final Resource resource,
            final boolean shouldFail
    ) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        JsonSchema schema = jsonSchemaFactory.getSchema(
                SchemaLocation.of(schemaId), config);
        Set<ValidationMessage> assertions = schema.validate(
                source,
                InputFormat.YAML,
                executionContext -> executionContext
                        .getExecutionConfig()
                        .setFormatAssertionsEnabled(true));
        if (shouldFail) {
            assertions.forEach(
                    assertion -> LOGGER.info(assertion.getMessage())
            );
            assertThat(assertions, is(not(empty())));
        } else {
            assertAll(
                    schemaId + " -> " + resource.getURI().getPath(),
                    assertions.stream().map(validationMessage ->
                            () -> fail(validationMessage.getMessage())));
        }
    }
}
