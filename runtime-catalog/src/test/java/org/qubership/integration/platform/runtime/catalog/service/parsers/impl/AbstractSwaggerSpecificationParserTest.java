package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.OpenApiMapperResolver;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.swagger.SwaggerSchemaResolver;
import org.qubership.integration.platform.runtime.catalog.service.schemas.SchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.ArraySchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.DefaultSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.FileSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.ObjectSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.StringSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.impl.UUIDSchemaProcessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Shared wiring for the version-specific Swagger parser tests. Builds a parser backed by the
 * real schema processors and resolvers, with the persistence and version services mocked, so
 * subclasses only describe specifications and assert on the resulting model.
 */
@ExtendWith(MockitoExtension.class)
abstract class AbstractSwaggerSpecificationParserTest {

    @Mock protected SystemModelRepository systemModelRepository;
    @Mock protected ParserUtils parserUtils;
    @Mock protected EnvironmentBaseService environmentBaseService;

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

        lenient().when(systemModelRepository.save(any(SystemModel.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(parserUtils.defineVersionName(any(), any())).thenReturn("1.0.0");
        lenient().when(parserUtils.defineVersion(any(), any())).thenReturn("1.0.0");

        parser = new SwaggerSpecificationParser(
                systemModelRepository,
                resolver,
                allProcessors,
                mapperResolver,
                parserUtils,
                environmentBaseService
        );
    }

    protected SystemModel parse(String specification) {
        return parse(specification, message -> { });
    }

    protected SystemModel parse(String specification, Consumer<String> messageHandler) {
        // IMPLEMENTED system with one environment keeps resolverSwaggerEnvironment() happy
        // when swagger-parser injects a default server.
        IntegrationSystem system = new IntegrationSystem("sys-id");
        system.setIntegrationSystemType(IntegrationSystemType.IMPLEMENTED);
        Environment env = new Environment();
        env.setId("env-id");
        system.addEnvironment(env);

        SpecificationGroup group = SpecificationGroup.builder().name("grp").build();
        group.setId("grp-id");
        group.setSystem(system);

        SpecificationSource source = new SpecificationSource();
        source.setSource(specification);

        return parser.enrichSpecificationGroup(group, List.of(source), new HashSet<>(), false, messageHandler);
    }
}
