package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.io.configuration.ObjectMapperAutoConfiguration;
import org.qubership.integration.platform.parsers.configuration.SpecificationParserConfiguration;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Each message schema is stored as the document writes it, with the $id, $schema, and definitions the resolver adds.
class AsyncapiSpecificationParserSchemaTest {

    private static final String V2 = """
            asyncapi: 2.6.0
            info: {title: Orders, version: 1.0.0}
            servers:
              local: {url: 'localhost:9092', protocol: kafka}
            channels:
              orders:
                subscribe:
                  operationId: receiveOrder
                  message: %s
            components: %s
            """;

    private static final String V3 = """
            asyncapi: 3.0.0
            info: {title: Orders, version: 1.0.0}
            servers:
              local: {host: 'localhost:9092', protocol: kafka}
            channels:
              orders:
                address: orders
                messages: %s
            operations:
              receiveOrder:
                action: receive
                channel: {$ref: '#/channels/orders'}
            components: %s
            """;

    private static final String ORDER_SCHEMA = "{type: object, properties: {id: {type: string}}}";
    private static final String CLOSED_OBJECT = "{type: object, additionalProperties: false, properties: {id: {type: string}}}";
    private static final String NULLABLE_STRING = "{type: [string, 'null']}";
    private static final String INTEGER_ENUM = "{type: integer, enum: [1, 2, 3]}";
    private static final String INTEGER_CONST = "{type: integer, const: 5}";
    private static final String OWN_DEFINITIONS = "{type: object, properties: {foo: {$ref: '#/definitions/Foo'}}, "
            + "definitions: {Foo: {type: string}}}";
    private static final String ANNOTATED = "{type: object, discriminator: kind, example: {kind: a}, deprecated: true, "
            + "x-owner: billing, properties: {kind: {type: string}}}";

    private static final String HEADERS_MESSAGE = "Headers: {headers: {type: object, properties: {traceId: {type: string}}}}";
    private static final String ORDER_MESSAGE = "Order: {payload: " + ORDER_SCHEMA + "}";
    private static final String ALIAS_MESSAGE = "Alias: {$ref: '#/components/messages/Order'}";
    private static final String JSON_SCHEMA_FORMAT = "'application/schema+json;version=draft-07'";
    private static final String CLOSED_ENTRY = "Closed: {schemaFormat: " + JSON_SCHEMA_FORMAT + ", schema: " + CLOSED_OBJECT + "}";
    private static final String CLOSED_SCHEMAS = "schemas: {" + CLOSED_ENTRY + "}";
    private static final String CLOSED_COMPONENT = "{" + CLOSED_SCHEMAS + "}";
    // Instance data and extension data that happen to look like a reference into a multi-format component.
    private static final String CLOSED_REF = "{$ref: '#/components/schemas/Closed/schema'}";
    private static final String LITERAL_REFS = "{type: object, properties: {marker: {const: " + CLOSED_REF + ", enum: ["
            + CLOSED_REF + "], default: " + CLOSED_REF + ", example: " + CLOSED_REF + ", examples: [" + CLOSED_REF + "], x-data: " + CLOSED_REF + "}}}";
    private static final String DEPENDENT_LITERAL = "{type: object, properties: {trigger: {type: string}}, "
            + "dependencies: {trigger: {properties: {marker: {const: {$ref: '#/components/schemas/Dependent'}}}}}}";
    private static final String AVRO_SCHEMA = "{schemaFormat: 'application/vnd.apache.avro;version=1.9.0', "
            + "schema: {type: record, name: Order, fields: [{name: id, type: string}]}}";

    private final YAMLMapper yamlMapper = new YAMLMapper();

    private final AsyncapiSpecificationParser parser = new SpecificationParserConfiguration()
            .libraryAsyncapiSpecificationParser(new ObjectMapperAutoConfiguration().primaryObjectMapper());

    static Stream<Arguments> documents() {
        return Stream.of(
                // A message with headers and no payload.
                Arguments.of("v2 headers only",
                        v2(ref("Headers"), messages(HEADERS_MESSAGE)),
                        "Headers", "{}"),
                Arguments.of("v2 headers only in oneOf",
                        v2("{oneOf: [" + ref("Headers") + ", " + ref("Order") + "]}", messages(HEADERS_MESSAGE, ORDER_MESSAGE)),
                        "Headers", "{}"),
                Arguments.of("v3 headers only",
                        v3("{order: " + ref("Headers") + "}", messages(HEADERS_MESSAGE)),
                        "Headers", "{}"),
                // A message component that is itself a reference to another message.
                Arguments.of("v2 message alias",
                        v2(ref("Alias"), messages(ALIAS_MESSAGE, ORDER_MESSAGE)),
                        "Alias", ORDER_SCHEMA),
                Arguments.of("v3 message alias",
                        v3("{order: " + ref("Alias") + "}", messages(ALIAS_MESSAGE, ORDER_MESSAGE)),
                        "Alias", ORDER_SCHEMA),
                Arguments.of("v3 chain of message aliases",
                        v3("{order: " + ref("Chained") + "}",
                                messages("Chained: {$ref: '#/components/messages/Middle'}",
                                        "Middle: {$ref: '#/components/messages/Alias'}", ALIAS_MESSAGE, ORDER_MESSAGE)),
                        "Chained", ORDER_SCHEMA),
                // Keywords the root of a payload used to lose or fail on.
                Arguments.of("v2 additionalProperties false",
                        v2(payload(CLOSED_OBJECT), "{}"),
                        "payload", CLOSED_OBJECT),
                Arguments.of("v3 additionalProperties false",
                        v3("{order: " + payload(CLOSED_OBJECT) + "}", "{}"),
                        "payload", CLOSED_OBJECT),
                Arguments.of("v2 component additionalProperties false",
                        v2(payload("{$ref: '#/components/schemas/Closed'}"), "{schemas: {Closed: " + CLOSED_OBJECT + "}}"),
                        "payload", CLOSED_OBJECT),
                Arguments.of("v2 type array",
                        v2(payload(NULLABLE_STRING), "{}"),
                        "payload", NULLABLE_STRING),
                Arguments.of("v2 integer enum",
                        v2(payload(INTEGER_ENUM), "{}"),
                        "payload", INTEGER_ENUM),
                Arguments.of("v2 component integer const",
                        v2(payload("{$ref: '#/components/schemas/Five'}"), "{schemas: {Five: " + INTEGER_CONST + "}}"),
                        "payload", INTEGER_CONST),
                Arguments.of("v2 own definitions",
                        v2(payload(OWN_DEFINITIONS), "{}"),
                        "payload", OWN_DEFINITIONS),
                Arguments.of("v2 annotations",
                        v2(payload(ANNOTATED), "{}"),
                        "payload", ANNOTATED),
                Arguments.of("v3 headers keep the payload",
                        v3("{order: {headers: " + CLOSED_OBJECT + ", payload: " + INTEGER_ENUM + "}}", "{}"),
                        "payload", INTEGER_ENUM),
                // An AsyncAPI 3.0 multi-format schema in an AsyncAPI or JSON Schema format is the schema it holds,
                // and an empty schema in any other format.
                Arguments.of("v3 multi-format AsyncAPI schema",
                        v3("{order: " + payload("{schemaFormat: 'application/vnd.aai.asyncapi+json;version=3.0.0', schema: "
                                + CLOSED_OBJECT + "}") + "}", "{}"),
                        "payload", CLOSED_OBJECT),
                Arguments.of("v3 multi-format JSON Schema",
                        v3("{order: " + ref("Multi") + "}", messages("Multi: {payload: {schemaFormat: "
                                + "'application/schema+yaml;version=draft-07', schema: " + INTEGER_ENUM + "}}")),
                        "Multi", INTEGER_ENUM),
                Arguments.of("v3 multi-format in a channel with two messages",
                        v3("{order: " + payload("{schemaFormat: " + JSON_SCHEMA_FORMAT + ", schema: " + CLOSED_OBJECT + "}")
                                + ", other: " + payload(INTEGER_ENUM) + "}", "{}"),
                        "payload_0", CLOSED_OBJECT),
                Arguments.of("v3 multi-format boolean schema",
                        v3("{order: " + payload("{schemaFormat: " + JSON_SCHEMA_FORMAT + ", schema: true}") + "}", "{}"),
                        "payload", "{}"),
                Arguments.of("v3 multi-format Avro payload",
                        v3("{order: " + payload(AVRO_SCHEMA) + "}", "{}"),
                        "payload", "{}"),
                Arguments.of("v3 multi-format JSON Schema component",
                        v3("{order: " + payload("{$ref: '#/components/schemas/Closed'}") + "}",
                                CLOSED_COMPONENT),
                        "payload", CLOSED_OBJECT),
                Arguments.of("v3 schema with a schemaFormat keyword and no schema",
                        v3("{order: " + payload("{$ref: '#/components/schemas/Noted'}") + "}",
                                "{schemas: {Noted: {type: object, schemaFormat: note, properties: {id: {type: string}}}}}"),
                        "payload", "{type: object, schemaFormat: note, properties: {id: {type: string}}}"),
                // A reference into a multi-format schema follows it to the schema it holds.
                Arguments.of("v3 reference into a multi-format component",
                        v3("{order: " + payload("{$ref: '#/components/schemas/Closed/schema'}") + "}", CLOSED_COMPONENT),
                        "payload", CLOSED_OBJECT),
                Arguments.of("v3 component message with a reference into a multi-format component",
                        v3("{order: " + ref("Order") + "}", "{messages: {Order: {payload: {$ref: '#/components/schemas/Closed/schema'}}}, "
                                + CLOSED_SCHEMAS + "}"),
                        "Order", CLOSED_OBJECT),
                Arguments.of("v3 component schema with a reference into a multi-format component",
                        v3("{order: " + payload("{$ref: '#/components/schemas/Wrapper'}") + "}",
                                "{schemas: {Wrapper: {type: object, properties: {closed: " + CLOSED_REF + "}}, " + CLOSED_ENTRY + "}}"),
                        "payload", "{type: object, properties: {closed: {$ref: '#/definitions/Closed'}}, "
                                + "definitions: {Closed: " + CLOSED_OBJECT + "}}"),
                Arguments.of("v3 nested reference into a multi-format component",
                        v3("{order: " + payload("{type: object, properties: {closed: {$ref: '#/components/schemas/Closed/schema'}}}")
                                + "}", CLOSED_COMPONENT),
                        "payload", "{type: object, properties: {closed: {$ref: '#/definitions/Closed'}}, "
                                + "definitions: {Closed: " + CLOSED_OBJECT + "}}"),
                Arguments.of("v3 reference below the schema of a multi-format component",
                        v3("{order: " + payload("{$ref: '#/components/schemas/Closed/schema/properties/id'}") + "}",
                                CLOSED_COMPONENT),
                        "payload", "{type: string}"),
                Arguments.of("v3 reference into a multi-format message payload",
                        v3("{order: " + payload("{$ref: '#/components/messages/Multi/payload/schema'}") + "}",
                                messages("Multi: {payload: {schemaFormat: " + JSON_SCHEMA_FORMAT + ", schema: " + INTEGER_ENUM + "}}")),
                        "payload", INTEGER_ENUM),
                Arguments.of("v3 reference-shaped literal values next to a multi-format component",
                        v3("{order: " + payload(LITERAL_REFS) + "}", CLOSED_COMPONENT),
                        "payload", LITERAL_REFS),
                Arguments.of("v3 reference from a property named like a keyword",
                        v3("{order: " + payload("{type: object, properties: {default: " + CLOSED_REF + "}}") + "}", CLOSED_COMPONENT),
                        "payload", "{type: object, properties: {default: {$ref: '#/definitions/Closed'}}, "
                                + "definitions: {Closed: " + CLOSED_OBJECT + "}}"),
                Arguments.of("v3 reference under dependencies",
                        v3("{order: " + payload("{type: object, properties: {a: {type: string}}, "
                                + "dependencies: {a: {$ref: '#/components/schemas/Dependent'}, "
                                + "c: {properties: {d: {$ref: '#/components/schemas/Dependent'}}}}}") + "}",
                                "{schemas: {Dependent: {type: object, required: [b]}}}"),
                        "payload", "{type: object, properties: {a: {type: string}}, "
                                + "dependencies: {a: {$ref: '#/definitions/Dependent'}, "
                                + "c: {properties: {d: {$ref: '#/definitions/Dependent'}}}}, "
                                + "definitions: {Dependent: {type: object, required: [b]}}}"),
                Arguments.of("v3 array with dependencies",
                        v3("{order: " + payload("{type: array, items: {$ref: '#/components/schemas/Dependent'}, "
                                + "dependencies: {a: [b]}}") + "}",
                                "{schemas: {Dependent: {type: object, required: [b]}}}"),
                        "payload", "{type: array, items: {$ref: '#/definitions/Dependent'}, dependencies: {a: [b]}, "
                                + "definitions: {Dependent: {type: object, required: [b]}}}"),
                Arguments.of("v3 reference-shaped literal under dependencies",
                        v3("{order: " + payload(DEPENDENT_LITERAL) + "}", "{schemas: {Dependent: {type: object, required: [b]}}}"),
                        "payload", DEPENDENT_LITERAL),
                Arguments.of("v3 multi-format Avro component",
                        v3("{order: " + payload("{$ref: '#/components/schemas/Order'}") + "}",
                                "{schemas: {Order: " + AVRO_SCHEMA + "}}"),
                        "payload", "{}"),
                // A component inlined into definitions without its $id, so its references stay relative to the root.
                Arguments.of("v2 component with its own $id",
                        v2(payload("{type: object, properties: {order: {$ref: '#/components/schemas/Order'}}}"), """
                                {schemas: {
                                  Order: {$id: 'https://example.com/order.json', type: object,
                                          properties: {address: {$ref: '#/components/schemas/Address'}}},
                                  Address: {type: object, properties: {street: {type: string}}}}}
                                """),
                        "payload", """
                                {type: object, properties: {order: {$ref: '#/definitions/Order'}}, definitions: {
                                  Order: {type: object, properties: {address: {$ref: '#/definitions/Address'}}},
                                  Address: {type: object, properties: {street: {type: string}}}}}
                                """),
                // The same keywords below the root, which were stored unchanged before.
                Arguments.of("v2 inline oneOf",
                        v2("{oneOf: [" + payload(CLOSED_OBJECT) + "]}", "{}"),
                        "payload", CLOSED_OBJECT)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    void storesTheMessageSchemaAsWritten(String name, String document, String key, String expected) throws Exception {
        ParsedSystemModel model = parser.toSystemModel(parser.read(document), "kafka");

        ParsedOperation operation = model.getOperations().get(0);
        assertTrue(operation.getResponseSchemas().containsKey(key), () -> "keys: " + operation.getResponseSchemas().keySet());
        ObjectNode stored = operation.getResponseSchemas().get(key).deepCopy();
        stored.remove(List.of("$id", "$schema"));
        ObjectNode written = (ObjectNode) yamlMapper.readTree(expected);
        written.putIfAbsent("definitions", yamlMapper.createObjectNode());
        assertEquals(written, stored);
    }

    @Test
    void importsACycleOfMessageAliases() {
        String document = v3("{order: " + ref("First") + "}", messages(
                "First: {$ref: '#/components/messages/Second'}", "Second: {$ref: '#/components/messages/Third'}",
                "Third: {$ref: '#/components/messages/Second'}"));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> parser.toSystemModel(parser.read(document), "kafka"));
    }

    private static String v2(String message, String components) {
        return V2.formatted(message, components);
    }

    private static String v3(String messages, String components) {
        return V3.formatted(messages, components);
    }

    private static String ref(String message) {
        return "{$ref: '#/components/messages/" + message + "'}";
    }

    private static String payload(String schema) {
        return "{payload: " + schema + "}";
    }

    private static String messages(String... messages) {
        return "{messages: {" + String.join(", ", messages) + "}}";
    }
}
