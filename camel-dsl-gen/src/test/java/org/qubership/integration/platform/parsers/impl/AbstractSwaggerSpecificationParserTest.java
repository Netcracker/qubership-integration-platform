package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.resolvers.SwaggerSchemaResolver;
import org.qubership.integration.platform.parsers.schemas.SchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ArraySchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.DefaultSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.FileSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ObjectSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.StringSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.UUIDSchemaProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared wiring for the version-specific Swagger parser tests. Builds a parser backed by the real
 * schema processors and resolvers, so subclasses only describe specifications and assert on the
 * resulting model.
 */
abstract class AbstractSwaggerSpecificationParserTest {

    protected SwaggerSpecificationParser parser;

    @BeforeEach
    void setUpParser() {
        ObjectMapper mapper = Json.mapper();
        SwaggerSchemaResolver resolver = new SwaggerSchemaResolver();
        OpenApiMapperResolver mapperResolver = new OpenApiMapperResolver();

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

        parser = new SwaggerSpecificationParser(resolver, allProcessors, mapperResolver);
    }

    protected ParsedSystemModel parse(String specification) {
        return parse(specification, message -> { });
    }

    protected ParsedSystemModel parse(String specification, Consumer<String> messageHandler) {
        SpecificationSource source = new SpecificationSource("spec", specification);
        return parser.parseSpecification("grp-id", List.of(source), messageHandler);
    }
}
