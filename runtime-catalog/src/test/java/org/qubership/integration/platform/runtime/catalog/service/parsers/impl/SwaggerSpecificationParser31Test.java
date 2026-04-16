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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.RuntimeCatalogApplicationRunner;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SwaggerSpecificationParser31Test {

    @Mock private SystemModelRepository systemModelRepository;
    @Mock private ParserUtils parserUtils;
    @Mock private EnvironmentBaseService environmentBaseService;

    private SwaggerSpecificationParser parser;

    @BeforeAll
    static void ensureBindTypeIsSet() throws ClassNotFoundException {
        // RuntimeCatalogApplicationRunner's static initializer sets bind-type=true so
        // io.swagger.v3.oas.models.media.Schema#getType() falls back to `types` when
        // parsing OpenAPI 3.1. Force class initialization and assert the side effect
        // — if someone removes the static block, this test fails loudly.
        Class.forName(RuntimeCatalogApplicationRunner.class.getName());
        assertEquals("true", System.getProperty("bind-type"));
    }

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = Json.mapper();
        SwaggerSchemaResolver resolver = new SwaggerSchemaResolver();

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

        when(systemModelRepository.save(any(SystemModel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parserUtils.defineVersionName(any(), any())).thenReturn("1.0.0");
        when(parserUtils.defineVersion(any(), any())).thenReturn("1.0.0");

        parser = new SwaggerSpecificationParser(
                systemModelRepository,
                resolver,
                allProcessors,
                mapper,
                parserUtils,
                environmentBaseService
        );
    }

    @Test
    @DisplayName("OpenAPI 3.1: `type` is preserved on properties and $ref is inlined into definitions")
    void openApi31PreservesTypeAndResolvesRefs() {
        String spec = """
                {
                  "openapi": "3.1.0",
                  "info": {"title": "Test", "version": "1.0.0"},
                  "paths": {
                    "/things": {
                      "post": {
                        "operationId": "createThing",
                        "requestBody": {
                          "required": true,
                          "content": {
                            "application/json": {
                              "schema": {"$ref": "#/components/schemas/ThingRequest"}
                            }
                          }
                        },
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {
                              "application/json": {
                                "schema": {"$ref": "#/components/schemas/ThingResponse"}
                              }
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "ThingRequest": {
                        "properties": {
                          "name": {"type": "string"},
                          "labels": {
                            "type": "array",
                            "items": {"$ref": "#/components/schemas/LabelDTO"}
                          }
                        }
                      },
                      "ThingResponse": {
                        "properties": {
                          "id": {"type": "string"}
                        }
                      },
                      "LabelDTO": {
                        "properties": {
                          "name": {"type": "string"}
                        }
                      }
                    }
                  }
                }
                """;

        // IMPLEMENTED type + one environment is enough to keep
        // resolverSwaggerEnvironment() happy when swagger-parser injects a default server.
        Environment env = new Environment();
        env.setId("env-id");
        IntegrationSystem system = new IntegrationSystem("sys-id");
        system.setIntegrationSystemType(IntegrationSystemType.IMPLEMENTED);
        system.addEnvironment(env);

        SpecificationGroup group = SpecificationGroup.builder().name("grp").build();
        group.setId("grp-id");
        group.setSystem(system);

        SpecificationSource source = new SpecificationSource();
        source.setSource(spec);

        SystemModel model = parser.enrichSpecificationGroup(
                group, List.of(source), new HashSet<>(), false, msg -> { });

        assertNotNull(model);
        assertEquals(1, model.getOperations().size());

        Operation op = model.getOperations().get(0);

        JsonNode requestSchema = op.getRequestSchema().get("application/json");
        assertNotNull(requestSchema, "request schema for application/json is missing");

        // 1. `type` survives on primitive properties — the bind-type fix's main effect.
        //    (The top-level ThingRequest schema has no `type` in source; that's valid 3.1
        //    where the shape is implied by `properties`. We're checking leaf types.)
        assertEquals("string", requestSchema.at("/properties/name/type").asText());
        assertEquals("array", requestSchema.at("/properties/labels/type").asText());

        // 2. Nested $ref is rewritten to #/definitions/... and the target is inlined.
        //    This exercises the resolver's structural recursion (no `type` on top level).
        assertEquals("#/definitions/LabelDTO",
                requestSchema.at("/properties/labels/items/$ref").asText());
        JsonNode labelDef = requestSchema.at("/definitions/LabelDTO");
        assertFalse(labelDef.isMissingNode(), "LabelDTO must be inlined into definitions");
        assertEquals("string", labelDef.at("/properties/name/type").asText());

        // Response schema gets the same treatment.
        JsonNode responseSchema = op.getResponseSchemas().get("200").get("application/json");
        assertNotNull(responseSchema, "response schema for 200/application/json is missing");
        assertEquals("string", responseSchema.at("/properties/id/type").asText());
    }
}
