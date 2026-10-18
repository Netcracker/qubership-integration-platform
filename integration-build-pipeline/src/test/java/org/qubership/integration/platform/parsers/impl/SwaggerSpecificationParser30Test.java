package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void inlineSchemaReferencesAreResolvedIntoDefinitions() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/orders": {
                      "post": {
                        "operationId": "createOrder",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {
                                "type": "object",
                                "properties": {"address": {"$ref": "#/components/schemas/Address"}}
                              }
                            }
                          }
                        },
                        "responses": {"204": {"description": "No content"}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Address": {"type": "object", "properties": {"city": {"type": "string"}}}
                    }
                  }
                }
                """;

        JsonNode requestSchema = parse(spec).getOperations().getFirst().getRequestSchema().get("application/json");

        assertEquals("#/definitions/Address", requestSchema.at("/properties/address/$ref").asText());
        assertEquals("string", requestSchema.at("/definitions/Address/properties/city/type").asText());
    }

    @Test
    void inlineSchemaWithoutReferencesGetsNoDefinitions() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/orders": {
                      "post": {
                        "operationId": "createOrder",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"type": "object", "properties": {"id": {"type": "string"}}}
                            }
                          }
                        },
                        "responses": {"204": {"description": "No content"}}
                      }
                    }
                  }
                }
                """;

        JsonNode requestSchema = parse(spec).getOperations().getFirst().getRequestSchema().get("application/json");

        assertEquals("string", requestSchema.at("/properties/id/type").asText());
        assertFalse(requestSchema.has("definitions"));
    }

    @Test
    void referencesNestedInMapsAreResolvedIntoDefinitions() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/regions": {
                      "post": {
                        "operationId": "createRegions",
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/Regions"}}
                          }
                        },
                        "responses": {"204": {"description": "No content"}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Regions": {
                        "type": "object",
                        "additionalProperties": {
                          "type": "object",
                          "additionalProperties": {"$ref": "#/components/schemas/Address"}
                        }
                      },
                      "Address": {"type": "object", "properties": {"city": {"type": "string"}}}
                    }
                  }
                }
                """;

        JsonNode requestSchema = parse(spec).getOperations().getFirst().getRequestSchema().get("application/json");

        assertEquals("#/definitions/Address", requestSchema.at("/additionalProperties/additionalProperties/$ref").asText());
        assertEquals("string", requestSchema.at("/definitions/Address/properties/city/type").asText());
    }

    @Test
    void referenceAsMapValueIsResolvedIntoDefinitions() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/regions": {
                      "post": {
                        "operationId": "createRegions",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"type": "object", "additionalProperties": {"$ref": "#/components/schemas/Address"}}
                            }
                          }
                        },
                        "responses": {"204": {"description": "No content"}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Address": {"type": "object", "properties": {"city": {"type": "string"}}}
                    }
                  }
                }
                """;

        JsonNode requestSchema = parse(spec).getOperations().getFirst().getRequestSchema().get("application/json");

        assertEquals("#/definitions/Address", requestSchema.at("/additionalProperties/$ref").asText());
        assertEquals("string", requestSchema.at("/definitions/Address/properties/city/type").asText());
    }

    @Test
    void externalReferenceInInlineSchemaIsKeptAsWritten() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/orders": {
                      "post": {
                        "operationId": "createOrder",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {
                                "type": "object",
                                "properties": {"address": {"$ref": "./common.yaml#/components/schemas/Address"}}
                              }
                            }
                          }
                        },
                        "responses": {"204": {"description": "No content"}}
                      }
                    }
                  }
                }
                """;

        JsonNode requestSchema = parse(spec).getOperations().getFirst().getRequestSchema().get("application/json");

        assertEquals("./common.yaml#/components/schemas/Address", requestSchema.at("/properties/address/$ref").asText());
        assertFalse(requestSchema.has("definitions"));
    }

    @Test
    void inlineArrayOfInlineObjectsKeepsItemSchema() {
        String arraySchema = """
                {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "address": {"$ref": "#/components/schemas/Address"}
                    }
                  }
                }
                """;
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/orders": {
                      "post": {
                        "operationId": "createOrders",
                        "requestBody": {"content": {"application/json": {"schema": %1$s}}},
                        "responses": {"200": {"description": "OK", "content": {"application/json": {"schema": %1$s}}}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Address": {"type": "object", "properties": {"city": {"type": "string"}}}
                    }
                  }
                }
                """.formatted(arraySchema);

        ParsedOperation op = parse(spec).getOperations().getFirst();

        for (JsonNode schema : List.of(
                op.getRequestSchema().get("application/json"),
                op.getResponseSchemas().get("200").get("application/json"))) {
            assertEquals("array", schema.at("/type").asText());
            assertEquals("object", schema.at("/items/type").asText());
            assertEquals("string", schema.at("/items/properties/id/type").asText());
            assertEquals("#/definitions/Address", schema.at("/items/properties/address/$ref").asText());
            assertEquals("string", schema.at("/definitions/Address/properties/city/type").asText());
            // A draft-07 validator resolves #/definitions against the nearest $id, so it must stay at the root.
            assertFalse(schema.get("items").has("$id"));
            assertEquals("http://json-schema.org/draft-07/schema#", schema.at("/$schema").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\": \"string\", \"enum\": [\"A\", \"B\"]}",
        "{\"type\": \"string\", \"format\": \"uuid\"}",
        "{\"type\": \"array\", \"items\": {\"type\": \"integer\"}}"
    })
    void inlineArrayKeepsInlineItemSchema(String itemSchema) throws Exception {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/codes": {
                      "get": {
                        "operationId": "listCodes",
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {"application/json": {"schema": {"type": "array", "items": %s}}}
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(itemSchema);

        JsonNode responseSchema = parse(spec).getOperations().getFirst().getResponseSchemas().get("200").get("application/json");

        assertEquals("array", responseSchema.at("/type").asText());
        assertEquals(Json.mapper().readTree(itemSchema), responseSchema.get("items"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\": \"array\", \"items\": {\"$ref\": \"#/components/schemas/Address\"}}",
        "{\"$ref\": \"#/components/schemas/Address\"}",
        "{\"allOf\": [{\"$ref\": \"#/components/schemas/Address\"}, {\"type\": \"object\", \"properties\": {\"zip\": {\"type\": \"string\"}}}]}",
        "{\"type\": \"object\", \"additionalProperties\": {\"$ref\": \"#/components/schemas/Address\"}}",
        "{\"type\": \"string\", \"enum\": [\"A\", \"B\"]}",
        "{\"type\": \"object\", \"description\": \"Strict\", \"additionalProperties\": false, \"properties\": {\"city\": {\"type\": \"string\"}}}"
    })
    void arrayOfReferencesKeepsItemComponent(String itemComponent) throws Exception {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test 3.0", "version": "1.0.0"},
                  "paths": {
                    "/items": {
                      "get": {
                        "operationId": "listItems",
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {
                              "application/json": {
                                "schema": {"type": "array", "items": {"$ref": "#/components/schemas/Item"}}
                              }
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Item": %s,
                      "Address": {"type": "object", "properties": {"city": {"type": "string"}}}
                    }
                  }
                }
                """.formatted(itemComponent);

        JsonNode responseSchema = parse(spec).getOperations().getFirst().getResponseSchemas().get("200").get("application/json");

        assertEquals("array", responseSchema.at("/type").asText());
        assertEquals(Json.mapper().readTree(itemComponent.replace("#/components/schemas/", "#/definitions/")),
                responseSchema.get("items"));
        assertEquals(itemComponent.contains("$ref"), responseSchema.at("/definitions/Address").isObject());
        assertEquals("http://json-schema.org/draft-07/schema#", responseSchema.at("/$schema").asText());
    }
}
