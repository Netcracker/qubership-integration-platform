package org.qubership.integration.platform.runtime.catalog.service.resolvers.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsyncApiSchemaResolverTest {

    private AsyncApiSchemaResolver resolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        resolver = new AsyncApiSchemaResolver();
    }

    @Test
    void resolveRefWithInlinePayload() throws Exception {
        ObjectNode components = objectMapper.createObjectNode();
        ObjectNode messages = components.putObject("messages");
        ObjectNode msg = messages.putObject("TestMsg");
        ObjectNode payload = msg.putObject("payload");
        payload.put("type", "object");
        ObjectNode props = payload.putObject("properties");
        props.putObject("id").put("type", "string");

        String result = resolver.resolveRef("#/components/messages/TestMsg", components);
        JsonNode schema = objectMapper.readTree(result);

        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("id"));
        assertTrue(schema.has("$id"));
        assertTrue(schema.has("$schema"));
    }

    @Test
    void resolveRefWithPayloadRefToComponentSchema() throws Exception {
        ObjectNode components = objectMapper.createObjectNode();

        ObjectNode schemas = components.putObject("schemas");
        ObjectNode eventSchema = schemas.putObject("Event");
        eventSchema.put("type", "object");
        ObjectNode eventProps = eventSchema.putObject("properties");
        eventProps.putObject("name").put("type", "string");
        eventProps.putObject("value").put("type", "integer");

        ObjectNode messages = components.putObject("messages");
        ObjectNode msg = messages.putObject("EventMsg");
        msg.putObject("payload").put("$ref", "#/components/schemas/Event");

        String result = resolver.resolveRef("#/components/messages/EventMsg", components);
        JsonNode schema = objectMapper.readTree(result);

        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").has("name"));
        assertTrue(schema.get("properties").has("value"));
    }

    @Test
    void resolveRefWithPayloadRefAndNestedRefs() throws Exception {
        ObjectNode components = objectMapper.createObjectNode();

        ObjectNode schemas = components.putObject("schemas");

        ObjectNode addressSchema = schemas.putObject("Address");
        addressSchema.put("type", "object");
        addressSchema.putObject("properties").putObject("city").put("type", "string");

        ObjectNode userSchema = schemas.putObject("User");
        userSchema.put("type", "object");
        ObjectNode userProps = userSchema.putObject("properties");
        userProps.putObject("name").put("type", "string");
        userProps.putObject("address").put("$ref", "#/components/schemas/Address");

        ObjectNode messages = components.putObject("messages");
        messages.putObject("UserMsg").putObject("payload").put("$ref", "#/components/schemas/User");

        String result = resolver.resolveRef("#/components/messages/UserMsg", components);
        JsonNode schema = objectMapper.readTree(result);

        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").has("name"));
        assertTrue(schema.get("properties").has("address"));
        assertTrue(schema.has("definitions"));
        assertTrue(schema.get("definitions").has("Address"));
        assertEquals("object", schema.get("definitions").get("Address").get("type").asText());
    }

    @Test
    void resolveRefWithPayloadRefToMissingSchema() throws Exception {
        ObjectNode components = objectMapper.createObjectNode();
        components.putObject("schemas");

        ObjectNode messages = components.putObject("messages");
        messages.putObject("Msg").putObject("payload").put("$ref", "#/components/schemas/Missing");

        String result = resolver.resolveRef("#/components/messages/Msg", components);
        JsonNode schema = objectMapper.readTree(result);

        assertTrue(schema.has("$id"));
        assertTrue(schema.has("$schema"));
    }

    @Test
    void resolveRefWithEmptyMessage() throws Exception {
        ObjectNode components = objectMapper.createObjectNode();
        components.putObject("messages").putObject("Empty");

        String result = resolver.resolveRef("#/components/messages/Empty", components);
        JsonNode schema = objectMapper.readTree(result);

        assertTrue(schema.has("$id"));
        assertTrue(schema.has("definitions"));
    }

    @Test
    void resolveRefWithMissingMessage() throws Exception {
        ObjectNode components = objectMapper.createObjectNode();
        components.putObject("messages");

        String result = resolver.resolveRef("#/components/messages/NonExistent", components);
        JsonNode schema = objectMapper.readTree(result);

        assertTrue(schema.has("$schema"));
    }
}
