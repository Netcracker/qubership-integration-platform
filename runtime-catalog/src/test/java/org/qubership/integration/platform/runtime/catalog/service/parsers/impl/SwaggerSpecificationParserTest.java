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

package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.parsers.impl.OpenApiMapperResolver;
import org.qubership.integration.platform.parsers.resolvers.SwaggerSchemaResolver;
import org.qubership.integration.platform.parsers.schemas.SchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ArraySchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.DefaultSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.FileSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.ObjectSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.StringSchemaProcessor;
import org.qubership.integration.platform.parsers.schemas.impl.UUIDSchemaProcessor;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.EnvironmentRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.SystemBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Characterization tests that pin Swagger's per-protocol environment reconcile in the catalog
 * wrapper. They are the regression oracle for the refactor that reads environments from the library
 * model instead of {@code OpenAPI.getServers()}: the placeholder-stripped address, the spec-group
 * fallback name, the HTTP default properties, and the EXTERNAL, INTERNAL, and IMPLEMENTED branches
 * must all behave exactly as before.
 */
@ExtendWith(MockitoExtension.class)
class SwaggerSpecificationParserTest {

    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private SystemBaseService systemBaseService;
    @Mock
    private ActionsLogService actionLogger;

    private SwaggerSpecificationParser parser;

    @BeforeEach
    void setUp() {
        EnvironmentBaseService environmentBaseService = new EnvironmentBaseService(
                environmentRepository,
                systemBaseService,
                actionLogger,
                new ObjectMapper(),
                new ParserUtils(new ObjectMapper()));
        parser = new SwaggerSpecificationParser(libraryParser(), environmentBaseService);
    }

    private org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser libraryParser() {
        ObjectMapper mapper = Json.mapper();
        SwaggerSchemaResolver resolver = new SwaggerSchemaResolver();
        OpenApiMapperResolver mapperResolver = new OpenApiMapperResolver();
        List<SchemaProcessor> leafProcessors = List.of(
                new DefaultSchemaProcessor(mapper),
                new ObjectSchemaProcessor(mapper),
                new StringSchemaProcessor(mapper),
                new UUIDSchemaProcessor(mapper),
                new FileSchemaProcessor(mapper));
        List<SchemaProcessor> allProcessors = new ArrayList<>(leafProcessors);
        allProcessors.add(new ArraySchemaProcessor(leafProcessors, mapper));
        return new org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser(
                resolver, allProcessors, mapperResolver);
    }

    private IntegrationSystem system(IntegrationSystemType type) {
        return IntegrationSystem.builder()
                .id("system-1")
                .name("test system")
                .integrationSystemType(type)
                .protocol(OperationProtocol.HTTP)
                .build();
    }

    private SpecificationGroup group(IntegrationSystem system) {
        SpecificationGroup group = SpecificationGroup.builder().name("orders").build();
        group.setSystem(system);
        return group;
    }

    private List<SpecificationSource> sources(String specification) {
        return List.of(SpecificationSource.builder().name("spec").source(specification).build());
    }

    private void answerSaveWithArgument() {
        when(environmentRepository.save(any(Environment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String specWithServer(String serverBlock) {
        return """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "test", "version": "1.0.0"},
                  "servers": [%s],
                  "paths": {}
                }
                """.formatted(serverBlock);
    }

    @Test
    @DisplayName("EXTERNAL: creates an environment with the stripped address and HTTP default properties")
    void externalCreatesEnvironmentWithHttpDefaults() {
        answerSaveWithArgument();
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);
        String spec = specWithServer("""
                {"url": "https://{host}/api/v1/", "description": "Primary",
                 "variables": {"host": {"default": "example.com"}}}""");

        parser.parseSpecification(group(system), sources(spec), message -> { });

        assertEquals(1, system.getEnvironments().size());
        Environment created = system.getEnvironments().get(0);
        assertEquals("Primary", created.getName());
        assertEquals("https://example.com/api/v1", created.getAddress());
        assertEquals(EnvironmentSourceType.MANUAL, created.getSourceType());
        assertHttpDefaultProperties(created);
    }

    @Test
    @DisplayName("EXTERNAL: a server with no description falls back to the spec-group name")
    void externalUsesSpecGroupFallbackName() {
        answerSaveWithArgument();
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);
        String spec = specWithServer("{\"url\": \"https://plain.example.com/\"}");

        parser.parseSpecification(group(system), sources(spec), message -> { });

        assertEquals(1, system.getEnvironments().size());
        Environment created = system.getEnvironments().get(0);
        assertEquals("Environment for orders specification group", created.getName());
        assertEquals("https://plain.example.com", created.getAddress());
    }

    @Test
    @DisplayName("INTERNAL: fills a blank address in place and sets HTTP default properties")
    void internalUpdatesBlankAddressInPlace() {
        answerSaveWithArgument();
        IntegrationSystem system = system(IntegrationSystemType.INTERNAL);
        Environment existing = Environment.builder()
                .id("env-1")
                .name("existing")
                .address("")
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .build();
        system.addEnvironment(existing);
        String spec = specWithServer("{\"url\": \"https://internal.example.com/\"}");

        parser.parseSpecification(group(system), sources(spec), message -> { });

        assertEquals(1, system.getEnvironments().size());
        Environment updated = system.getEnvironments().get(0);
        assertEquals("env-1", updated.getId());
        assertEquals("existing", updated.getName());
        assertEquals("https://internal.example.com", updated.getAddress());
        assertHttpDefaultProperties(updated);
    }

    @Test
    @DisplayName("IMPLEMENTED: sets HTTP default properties and leaves the existing address untouched")
    void implementedSetsDefaultPropertiesOnly() {
        IntegrationSystem system = system(IntegrationSystemType.IMPLEMENTED);
        Environment existing = Environment.builder()
                .id("env-1")
                .name("existing")
                .address("https://kept.example.com")
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .build();
        system.addEnvironment(existing);
        String spec = specWithServer("{\"url\": \"https://ignored.example.com/\"}");

        parser.parseSpecification(group(system), sources(spec), message -> { });

        assertEquals(1, system.getEnvironments().size());
        Environment result = system.getEnvironments().get(0);
        assertEquals("https://kept.example.com", result.getAddress());
        assertHttpDefaultProperties(result);
    }

    private void assertHttpDefaultProperties(Environment environment) {
        JsonNode properties = environment.getProperties();
        assertTrue(properties != null && properties.has("connectTimeout"),
                "HTTP default properties must be set on the environment");
        assertEquals("120000", properties.get("connectTimeout").asText());
        assertEquals("false", properties.get("getWithBody").asText());
    }
}
