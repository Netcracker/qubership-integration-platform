package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerSpecificationParser32Test extends AbstractSwaggerSpecificationParserTest {

    @Test
    @DisplayName("OpenAPI 3.2 imports through the 3.1 bridge: path operations import, QUERY is dropped")
    void openApi32ImportsThroughThreeOneBridge() {
        // The /search path holds only a 3.2 QUERY operation. swagger-parser 2.1.x has no
        // QUERY method, so the operation is dropped and /search yields nothing.
        String spec = """
                {
                  "openapi": "3.2.0",
                  "info": {"title": "Test 3.2", "version": "1.0.0"},
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
                        "responses": {"200": {"description": "OK"}}
                      }
                    },
                    "/search": {
                      "query": {
                        "operationId": "queryThings",
                        "responses": {"200": {"description": "OK"}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "ThingRequest": {
                        "properties": {
                          "name": {"type": "string"},
                          "count": {"type": ["integer", "null"]}
                        }
                      }
                    }
                  }
                }
                """;

        List<String> messages = new ArrayList<>();
        ParsedSystemModel model = parse(spec, messages::add);

        assertNotNull(model);

        List<String> operationNames = model.getOperations().stream().map(ParsedOperation::getName).toList();
        assertTrue(operationNames.contains("createThing"), "path operation must be imported");
        assertFalse(operationNames.contains("queryThings"), "QUERY operation is unsupported and must be dropped");

        // The bridge warns that 3.2 was parsed as 3.1.
        assertTrue(messages.stream().anyMatch(message -> message.contains("3.2")),
                "import must warn that OpenAPI 3.2 was parsed with the 3.1 parser");

        // Multi-type ["integer","null"] survives as a JSON Schema type array.
        ParsedOperation createThing = model.getOperations().stream()
                .filter(operation -> "createThing".equals(operation.getName()))
                .findFirst()
                .orElseThrow();
        JsonNode requestSchema = createThing.getRequestSchema().get("application/json");
        assertNotNull(requestSchema, "request schema for application/json is missing");
        assertEquals("string", requestSchema.at("/properties/name/type").asText());

        JsonNode countType = requestSchema.at("/properties/count/type");
        assertTrue(countType.isArray(), "nullable integer must keep its type array");
        List<String> declaredTypes = new ArrayList<>();
        countType.forEach(element -> declaredTypes.add(element.asText()));
        assertTrue(declaredTypes.contains("integer"), "type array must contain 'integer'");
        assertTrue(declaredTypes.contains("null"), "type array must contain 'null'");
    }
}
