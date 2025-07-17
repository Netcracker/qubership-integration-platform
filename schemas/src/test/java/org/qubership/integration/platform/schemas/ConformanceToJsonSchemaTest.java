package org.qubership.integration.platform.schemas;

import com.networknt.schema.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.Charset;
import java.util.Set;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.fail;

public class ConformanceToJsonSchemaTest {
    private static JsonSchema schema;

    @BeforeAll
    static void setUp() {
        // This creates a schema factory that will use Draft-07 as the default if $schema is not specified
        // in the schema data. If $schema is specified in the schema data, then that schema dialect will be used
        // instead and this version is ignored.
        JsonMetaSchema metaSchema = JsonMetaSchema.builder(JsonMetaSchema.getV7())
                .unknownKeywordFactory(DisallowUnknownKeywordFactory.getInstance())
                .build();
        JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V7,
                builder -> builder.metaSchema(metaSchema)
        );
        SchemaValidatorsConfig.Builder builder = SchemaValidatorsConfig.builder();

        // By default, the JDK regular expression implementation which is not ECMA 262 compliant is used
        // Note that setting this requires including optional dependencies
        // builder.regularExpressionFactory(GraalJSRegularExpressionFactory.getInstance());
        // builder.regularExpressionFactory(JoniRegularExpressionFactory.getInstance());
        SchemaValidatorsConfig config = builder.build();

        schema = jsonSchemaFactory.getSchema(SchemaLocation.of("http://json-schema.org/draft-07/schema"), config);
    }

    @ParameterizedTest
    @MethodSource("schemaResourceProvider")
    public void testConformanceToJsonSchema(Resource resource) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        Set<ValidationMessage> assertions = schema.validate(source, InputFormat.YAML, executionContext -> {
            executionContext.getExecutionConfig().setFormatAssertionsEnabled(true);
        });
        assertAll(resource.getURI().getPath(), assertions.stream().map(
                validationMessage -> () -> fail(validationMessage.getMessage())));
    }

    public static Stream<Resource> schemaResourceProvider() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:**/*.schema.yaml");
        return Stream.of(resources);
    }
}
