package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the persistence-free {@code parseOperations} core: {@code withSchemas=true} reproduces the
 * import-time output (structure plus schemas); {@code withSchemas=false} keeps structure only.
 */
class SwaggerSpecificationParserCoreTest extends AbstractSwaggerSpecificationParserTest {

    private static final String SPEC = """
            {
              "openapi": "3.1.0",
              "info": {"title": "Test", "version": "1.0.0"},
              "paths": {
                "/things": {
                  "post": {
                    "operationId": "createThing",
                    "requestBody": {
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
                  "ThingRequest": {"properties": {"name": {"type": "string"}}},
                  "ThingResponse": {"properties": {"id": {"type": "string"}}}
                }
              }
            }
            """;

    @Test
    @DisplayName("parseOperations(withSchemas=true) yields structure and request/response schemas")
    void parseOperationsWithSchemasProducesSchemas() {
        List<Operation> operations = parser.parseOperations(SPEC, true, message -> { });

        assertEquals(1, operations.size());
        Operation operation = operations.getFirst();
        assertEquals("createThing", operation.getName());
        assertEquals("/things", operation.getPath());
        assertEquals("POST", operation.getMethod());
        assertNotNull(operation.getSpecification());
        assertNotNull(operation.getRequestSchema());
        assertNotNull(operation.getRequestSchema().get("application/json"));
        assertNotNull(operation.getResponseSchemas());
        assertNotNull(operation.getResponseSchemas().get("200"));
    }

    @Test
    @DisplayName("parseOperations(withSchemas=false) keeps structure but leaves schema fields null")
    void parseOperationsWithoutSchemasKeepsStructureOnly() {
        List<Operation> withSchemas = parser.parseOperations(SPEC, true, message -> { });
        List<Operation> withoutSchemas = parser.parseOperations(SPEC, false, message -> { });

        assertEquals(withSchemas.size(), withoutSchemas.size());
        Operation full = withSchemas.getFirst();
        Operation structural = withoutSchemas.getFirst();

        assertEquals(full.getName(), structural.getName());
        assertEquals(full.getPath(), structural.getPath());
        assertEquals(full.getMethod(), structural.getMethod());
        assertEquals(full.getSpecification(), structural.getSpecification());

        assertNull(structural.getRequestSchema());
        assertNull(structural.getResponseSchemas());
    }

    private static final String SPEC_WITH_SUMMARY = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Test", "version": "1.0.0"},
              "paths": {
                "/pets": {
                  "get": {
                    "operationId": "listPets",
                    "summary": "List all pets",
                    "deprecated": true,
                    "responses": {"200": {"description": "OK"}}
                  }
                }
              }
            }
            """;

    @Test
    @DisplayName("parseOperations populates typed OpenapiOperation with method and path derived to today's values")
    void parseOperationsPopulatesTypedOpenapiOperation() {
        List<Operation> operations = parser.parseOperations(SPEC_WITH_SUMMARY, false, message -> { });

        assertEquals(1, operations.size());
        Operation operation = operations.getFirst();

        OpenapiOperation typed = assertInstanceOf(OpenapiOperation.class, operation.getTyped());
        assertEquals("List all pets", typed.summary());
        assertEquals("/pets", typed.path());
        assertEquals("get", typed.method());
        assertEquals(Boolean.TRUE, typed.isDeprecated());

        // Anti-regression: derived method and path must equal the pre-typed column values.
        assertEquals("GET", operation.getMethod());
        assertEquals("/pets", operation.getPath());
    }

    @Test
    @DisplayName("parseOperations wraps a raw deserializer failure into SpecificationImportException")
    void parseOperationsWrapsRawDeserializerFailure() {
        // Corrupt content makes the swagger-parser deserializer throw a raw RuntimeException from
        // DeserializationUtils. Before the fix that escaped unwrapped and surfaced as an HTTP 500 on the
        // read path; the core must now wrap it into a SpecificationImportException (message
        // SPECIFICATION_FILE_PROCESSING_ERROR, unlike the null-document INVALID_SWAGGER_FILE path) so the
        // on-demand read path degrades to null schemas, matching the GraphQL/AsyncAPI/Protobuf cores.
        SpecificationImportException exception = assertThrows(
                SpecificationImportException.class,
                () -> parser.parseOperations("openapi: 3.0.0\ninfo: {title: x", true, message -> { }));
        assertEquals(SpecificationParser.SPECIFICATION_FILE_PROCESSING_ERROR, exception.getMessage(),
                "malformed content must degrade through the wrap path, not surface a raw runtime exception");
        assertNotNull(exception.getCause(),
                "the wrap must chain the root parser exception so the degrade warn log keeps its stack trace");
    }

    // Only this document declares /orders, so importing the other one leaves nothing to find.
    private static final String FLAGGED_MAIN = """
            openapi: 3.0.3
            info: {title: Main, version: "1.0.0"}
            paths:
              /orders:
                post:
                  operationId: createOrder
                  responses:
                    "200": {description: ok}
            """;

    private static final String DECOY = """
            openapi: 3.0.3
            info: {title: Decoy, version: "1.0.0"}
            paths:
              /other:
                get:
                  operationId: getOther
                  responses:
                    "200": {description: ok}
            """;

    /**
     * Import and on-demand extraction have to read the same document. The extractor picks the source flagged as main;
     * import used to take the first of the collection, so a multi-source model parsed one document and re-derived its
     * schemas from another, missing every key.
     */
    @Test
    @DisplayName("import parses the flagged main source even when it is not first")
    void importSelectsTheFlaggedMainSource() {
        SystemModel model = importSources(List.of(source(DECOY, false), source(FLAGGED_MAIN, true)), message -> { });

        List<Operation> operations = model.getOperations();
        assertEquals(1, operations.size(), "the flagged main source declares exactly one operation");
        assertEquals("/orders", operations.get(0).getPath());
        assertEquals("createOrder", operations.get(0).getName());
    }

    private static SpecificationSource source(String content, boolean main) {
        SpecificationSource source = new SpecificationSource();
        source.setSource(content);
        source.setMainSource(main);
        return source;
    }
}
