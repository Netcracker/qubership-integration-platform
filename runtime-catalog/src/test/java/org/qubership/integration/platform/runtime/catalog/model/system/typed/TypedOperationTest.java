package org.qubership.integration.platform.runtime.catalog.model.system.typed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedOperationTest {

    // Mirrors the hypersistence static ObjectMapper that actually reads and writes the jsonb column.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void openapiDerivesUppercaseMethodAndPath() {
        OpenapiOperation operation = new OpenapiOperation("Add a new pet", "/pet", "post", false);
        assertEquals("POST", operation.deriveMethod());
        assertEquals("/pet", operation.derivePath());
    }

    @Test
    void asyncapiDerivesMethodAsIsAndChannelAsPath() {
        AsyncapiOperation operation = new AsyncapiOperation("consume orders", "orders.created", "subscribe");
        assertEquals("subscribe", operation.deriveMethod());
        assertEquals("orders.created", operation.derivePath());
    }

    @Test
    void wsdlDerivesPostAndEmptyPath() {
        WsdlOperation operation = new WsdlOperation("SOAP", "PaymentSoapBinding");
        assertEquals("POST", operation.deriveMethod());
        assertEquals("", operation.derivePath());
    }

    @Test
    void graphqlDerivesOperationTypeAndSdlPath() {
        GraphqlOperation operation = new GraphqlOperation("query", "customer(id: ID!): Customer");
        assertEquals("query", operation.deriveMethod());
        assertEquals("customer(id: ID!): Customer", operation.derivePath());
    }

    @Test
    void protobufDerivesRpcMethodAndJavaPackagePath() {
        ProtobufOperation operation = new ProtobufOperation(
                "acme.payments.v1", "PaymentService", "Authorize", "com.acme.payments.grpc");
        assertEquals("Authorize", operation.deriveMethod());
        assertEquals("com.acme.payments.grpc.PaymentService", operation.derivePath());
    }

    @Test
    void protobufFallsBackToProtoPackageWhenJavaPackageIsNull() {
        ProtobufOperation operation = new ProtobufOperation(
                "acme.payments.v1", "PaymentService", "Authorize", null);
        assertEquals("Authorize", operation.deriveMethod());
        assertEquals("acme.payments.v1.PaymentService", operation.derivePath());
    }

    @Test
    void openapiRoundTripsThroughJsonb() throws Exception {
        assertRoundTrip(new OpenapiOperation("Add a new pet", "/pet", "post", false), "openapi", OpenapiOperation.class);
    }

    @Test
    void asyncapiRoundTripsThroughJsonb() throws Exception {
        assertRoundTrip(new AsyncapiOperation("consume orders", "orders.created", "subscribe"),
                "asyncapi", AsyncapiOperation.class);
    }

    @Test
    void wsdlRoundTripsThroughJsonb() throws Exception {
        assertRoundTrip(new WsdlOperation("SOAP", "PaymentSoapBinding"), "wsdl", WsdlOperation.class);
    }

    @Test
    void graphqlRoundTripsThroughJsonb() throws Exception {
        assertRoundTrip(new GraphqlOperation("query", "customer(id: ID!): Customer"), "graphql", GraphqlOperation.class);
    }

    @Test
    void protobufRoundTripsThroughJsonb() throws Exception {
        assertRoundTrip(
                new ProtobufOperation("acme.payments.v1", "PaymentService", "Authorize", "com.acme.payments.grpc"),
                "protobuf", ProtobufOperation.class);
    }

    @Test
    void protobufSerializesQipFieldsUnderSchemaNames() throws Exception {
        String json = mapper.writeValueAsString(
                new ProtobufOperation("acme.payments.v1", "PaymentService", "Authorize", "com.acme.payments.grpc"));
        assertTrue(json.contains("\"package\":\"acme.payments.v1\""), json);
        assertTrue(json.contains("\"javaPackage\":\"com.acme.payments.grpc\""), json);
    }

    @Test
    void unknownPropertiesAreIgnoredOnRead() throws Exception {
        // A field dropped from a record must not make old rows unreadable; the mapper fails on unknown props by default.
        String json = "{\"type\":\"openapi\",\"path\":\"/pet\",\"method\":\"post\","
                + "\"summary\":\"Add a new pet\",\"isDeprecated\":false,\"removedField\":\"x\"}";
        TypedOperation operation = mapper.readValue(json, TypedOperation.class);
        assertInstanceOf(OpenapiOperation.class, operation);
        assertEquals("POST", operation.deriveMethod());
    }

    @Test
    void defaultAccessorsDoNotLeakIntoSerialization() throws Exception {
        // TypedOperation defaults every flat accessor to null so the entity can read one without an instanceof
        // chain. None is a bean getter, so a record must not gain a field it does not declare. isDeprecated is the
        // one to watch: Jackson treats an is-prefixed no-arg method returning Boolean as a getter on a plain bean.
        assertEquals(Set.of("type", "summary", "path", "method", "isDeprecated"),
                fieldNames(new OpenapiOperation("Add a new pet", "/pet", "post", false)));
        assertEquals(Set.of("type", "protocol", "binding"),
                fieldNames(new WsdlOperation("SOAP", "PaymentSoapBinding")));
        assertEquals(Set.of("type", "summary", "channel", "method"),
                fieldNames(new AsyncapiOperation("consume orders", "orders.created", "subscribe")));
        assertEquals(Set.of("type", "operationType", "sdl"),
                fieldNames(new GraphqlOperation("query", "customer(id: ID!): Customer")));
        // Protobuf is the one record that renames a component (@JsonProperty("package")) and the one whose three
        // accessors all became interface defaults, so pin that it emits the schema name and not both spellings.
        assertEquals(Set.of("type", "package", "service", "rpcMethod", "javaPackage"),
                fieldNames(new ProtobufOperation(
                        "acme.payments.v1", "PaymentService", "Authorize", "com.acme.payments.grpc")));
    }

    private Set<String> fieldNames(TypedOperation operation) throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(operation));
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertRoundTrip(TypedOperation original, String expectedType, Class<? extends TypedOperation> expectedClass)
            throws Exception {
        // Write by runtime type (as the jsonb layer does), read back by the declared interface type.
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"" + expectedType + "\""), json);
        TypedOperation restored = mapper.readValue(json, TypedOperation.class);
        assertInstanceOf(expectedClass, restored);
        assertEquals(original, restored);
        assertEquals(original.deriveMethod(), restored.deriveMethod());
        assertEquals(original.derivePath(), restored.derivePath());
    }
}
