package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerSpecificationParser30Test extends AbstractSwaggerSpecificationParserTest {

    @Test
    @DisplayName("OpenAPI 3.0: scalar type and the nullable keyword import unchanged via the legacy mapper")
    void openApi30ImportsWithLegacyMapper() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
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
                    }
                  },
                  "components": {
                    "schemas": {
                      "ThingRequest": {
                        "type": "object",
                        "properties": {
                          "name": {"type": "string"},
                          "nickname": {"type": "string", "nullable": true}
                        }
                      }
                    }
                  }
                }
                """;

        ParsedSystemModel model = parse(spec);

        assertNotNull(model);
        assertEquals(1, model.getOperations().size());

        ParsedOperation op = model.getOperations().getFirst();
        JsonNode requestSchema = op.getRequestSchema().get("application/json");
        assertNotNull(requestSchema, "request schema for application/json is missing");

        // Scalar type stays scalar; the 3.0 nullable keyword is preserved on the property.
        assertEquals("string", requestSchema.at("/properties/name/type").asText());
        assertEquals("string", requestSchema.at("/properties/nickname/type").asText());
        assertTrue(requestSchema.at("/properties/nickname/nullable").asBoolean(),
                "3.0 nullable keyword must survive import");
    }
}
