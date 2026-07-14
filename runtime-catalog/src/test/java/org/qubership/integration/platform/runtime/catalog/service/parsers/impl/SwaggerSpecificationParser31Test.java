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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerSpecificationParser31Test extends AbstractSwaggerSpecificationParserTest {

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

        ParsedSystemModel model = parse(spec);

        assertNotNull(model);
        assertEquals(1, model.getOperations().size());

        ParsedOperation op = model.getOperations().getFirst();

        JsonNode requestSchema = op.getRequestSchema().get("application/json");
        assertNotNull(requestSchema, "request schema for application/json is missing");

        // 1. `type` survives on primitive properties.
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

    @Test
    @DisplayName("OpenAPI 3.1: JSON Schema 2020-12 keywords (const, numeric exclusiveMinimum) survive import")
    void openApi31PreservesJsonSchemaKeywords() {
        String spec = """
                {
                  "openapi": "3.1.0",
                  "info": {"title": "Test", "version": "1.0.0"},
                  "paths": {
                    "/money": {
                      "post": {
                        "operationId": "createMoney",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"$ref": "#/components/schemas/Money"}
                            }
                          }
                        },
                        "responses": {"200": {"description": "OK"}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Money": {
                        "properties": {
                          "amount": {"type": "number", "exclusiveMinimum": 0},
                          "currency": {"const": "USD"}
                        }
                      }
                    }
                  }
                }
                """;

        ParsedSystemModel model = parse(spec);

        assertNotNull(model);
        ParsedOperation op = model.getOperations().getFirst();
        JsonNode requestSchema = op.getRequestSchema().get("application/json");
        assertNotNull(requestSchema, "request schema for application/json is missing");

        // 3.1 keeps exclusiveMinimum as a number, not the 3.0 boolean.
        JsonNode exclusiveMinimum = requestSchema.at("/properties/amount/exclusiveMinimum");
        assertFalse(exclusiveMinimum.isMissingNode(), "exclusiveMinimum must survive import");
        assertEquals(0, exclusiveMinimum.asInt());

        // const replaces a single-element enum in 3.1.
        assertEquals("USD", requestSchema.at("/properties/currency/const").asText());
    }

    @Test
    @DisplayName("OpenAPI 3.1: a top-level array response inlines its item $ref into definitions")
    void openApi31TopLevelArrayResponseInlinesItemRef() {
        String spec = """
                {
                  "openapi": "3.1.0",
                  "info": {"title": "Test", "version": "1.0.0"},
                  "paths": {
                    "/pets": {
                      "get": {
                        "operationId": "listPets",
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {
                              "application/json": {
                                "schema": {"$ref": "#/components/schemas/Pets"}
                              }
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Pet": {
                        "type": "object",
                        "required": ["id", "name"],
                        "properties": {
                          "id": {"type": "integer", "format": "int64"},
                          "name": {"type": "string"},
                          "tag": {"type": "string"}
                        }
                      },
                      "Pets": {
                        "type": "array",
                        "items": {"$ref": "#/components/schemas/Pet"}
                      }
                    }
                  }
                }
                """;

        ParsedSystemModel model = parse(spec);

        assertNotNull(model);
        ParsedOperation op = model.getOperations().getFirst();
        JsonNode responseSchema = op.getResponseSchemas().get("200").get("application/json");
        assertNotNull(responseSchema, "response schema for 200/application/json is missing");

        // The array keeps its shape and must NOT pick up a spurious top-level $ref.
        assertEquals("array", responseSchema.at("/type").asText());
        assertTrue(responseSchema.at("/$ref").isMissingNode(), "array schema must not get a spurious $ref");
        assertEquals("#/definitions/Pet", responseSchema.at("/items/$ref").asText());

        // The item component is inlined as the real object, not a copy of the array node.
        JsonNode petDef = responseSchema.at("/definitions/Pet");
        assertFalse(petDef.isMissingNode(), "Pet must be inlined into definitions");
        assertEquals("object", petDef.at("/type").asText());
        assertEquals("string", petDef.at("/properties/name/type").asText());
        assertTrue(petDef.at("/$ref").isMissingNode(), "inlined Pet must not be the self-referential array node");
    }

    @Test
    @DisplayName("OpenAPI 3.1: boolean schemas (items: false) do not break sibling $ref resolution")
    void openApi31BooleanSchemasDoNotBreakRefResolution() {
        // `items: false` is a JSON Schema 2020-12 boolean schema, valid in 3.1 (e.g. closing a
        // prefixItems tuple). The resolver must skip it as a non-ref node rather than fail.
        String spec = """
                {
                  "openapi": "3.1.0",
                  "info": {"title": "Test", "version": "1.0.0"},
                  "paths": {
                    "/images": {
                      "post": {
                        "operationId": "createImage",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"$ref": "#/components/schemas/Image"}
                            }
                          }
                        },
                        "responses": {"200": {"description": "OK"}}
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Image": {
                        "type": "object",
                        "properties": {
                          "tag": {"$ref": "#/components/schemas/Tag"},
                          "aspectRatio": {
                            "type": "array",
                            "prefixItems": [{"type": "integer"}, {"type": "integer"}],
                            "items": false
                          }
                        },
                        "additionalProperties": false
                      },
                      "Tag": {
                        "type": "object",
                        "properties": {"name": {"type": "string"}}
                      }
                    }
                  }
                }
                """;

        ParsedSystemModel model = parse(spec);

        assertNotNull(model);
        ParsedOperation op = model.getOperations().getFirst();
        JsonNode requestSchema = op.getRequestSchema().get("application/json");
        assertNotNull(requestSchema, "request schema for application/json is missing");

        // The boolean schema survives untouched.
        JsonNode items = requestSchema.at("/properties/aspectRatio/items");
        assertTrue(items.isBoolean(), "items: false must stay a boolean schema");
        assertFalse(items.asBoolean(), "items must remain false");

        // The sibling $ref still resolves and inlines despite the boolean schema next to it.
        assertEquals("#/definitions/Tag", requestSchema.at("/properties/tag/$ref").asText());
        assertEquals("string", requestSchema.at("/definitions/Tag/properties/name/type").asText());
    }
}
