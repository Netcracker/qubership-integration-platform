package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerSpecificationParser30Test extends AbstractSwaggerSpecificationParserTest {

    private static final String SPEC = """
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

    @Test
    @DisplayName("OpenAPI 3.0: scalar type and the nullable keyword survive schema production via the legacy mapper")
    void openApi30ProducesSchemasWithLegacyMapper() {
        List<Operation> operations = parseOperations(SPEC);

        assertEquals(1, operations.size());

        Operation op = operations.getFirst();
        JsonNode requestSchema = op.getRequestSchema().get("application/json");
        assertNotNull(requestSchema, "request schema for application/json is missing");

        // Scalar type stays scalar; the 3.0 nullable keyword is preserved on the property.
        assertEquals("string", requestSchema.at("/properties/name/type").asText());
        assertEquals("string", requestSchema.at("/properties/nickname/type").asText());
        assertTrue(requestSchema.at("/properties/nickname/nullable").asBoolean(),
                "3.0 nullable keyword must survive schema production");
    }

    @Test
    @DisplayName("Import keeps structural fields but produces no request/response schemas")
    void importProducesStructuralOperationsOnly() {
        SystemModel model = importSpec(SPEC);

        assertNotNull(model);
        assertEquals(1, model.getOperations().size());

        Operation op = model.getOperations().getFirst();
        assertEquals("createThing", op.getName());
        assertEquals("/things", op.getPath());
        assertEquals("POST", op.getMethod());
        assertNotNull(op.getSpecification(), "import must keep the specification slice");
        assertNull(op.getRequestSchema(), "import must not materialize the request schema");
        assertNull(op.getResponseSchemas(), "import must not materialize response schemas");
    }
}
