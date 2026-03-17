package org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSchemaResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AMQPSpecificationResolverTest {

    private AMQPSpecificationResolver resolver;
    private final YAMLMapper yamlMapper = new YAMLMapper();

    @BeforeEach
    void setUp() {
        resolver = new AMQPSpecificationResolver(new AsyncApiSchemaResolver());
    }

    @Test
    void setUpOperationMessagesWithInlinePayload() throws IOException {
        AsyncapiSpecification spec = readYaml("asyncapi/v2/amqp-v2-with-messages.yaml");
        Channel channel = spec.getChannels().get("notifications");

        List<OperationObject> ops = resolver.getOperationObjects(channel);
        assertEquals(2, ops.size(), "Should return both publish and subscribe operations");

        // subscribe operation has inline payload
        OperationObject subscribeOp = channel.getSubscribe();
        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, subscribeOp, spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(1, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("payload"));

        JsonNode payload = operation.getResponseSchemas().get("payload");
        assertEquals("object", payload.get("type").asText());
        assertTrue(payload.get("properties").has("to"));
        assertTrue(payload.get("properties").has("subject"));

        // full schema preserved: required fields must be present
        assertTrue(payload.has("required"));
        assertEquals(2, payload.get("required").size());
    }

    @Test
    void setUpOperationMessagesWithRef() throws IOException {
        AsyncapiSpecification spec = readYaml("asyncapi/v2/amqp-v2-with-messages.yaml");
        Channel channel = spec.getChannels().get("notifications");

        // publish operation has $ref
        OperationObject publishOp = channel.getPublish();
        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, publishOp, spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(1, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("Notification"));
    }

    @Test
    void setUpOperationMessagesWithRefOneOf() throws IOException {
        AsyncapiSpecification spec = readYaml("asyncapi/v2/amqp-v2-with-messages.yaml");
        Channel channel = spec.getChannels().get("alerts");

        List<OperationObject> ops = resolver.getOperationObjects(channel);
        assertEquals(1, ops.size(), "Only subscribe operation on alerts channel");

        OperationObject subscribeOp = channel.getSubscribe();
        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, subscribeOp, spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(2, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("CriticalAlert"));
        assertTrue(operation.getResponseSchemas().containsKey("WarningAlert"));
    }

    @Test
    void setUpOperationMessagesWithPayloadRef() throws IOException {
        AsyncapiSpecification spec = readYaml("asyncapi/v2/amqp-v2-payload-ref.yaml");
        Channel channel = spec.getChannels().get("events");

        OperationObject subscribeOp = channel.getSubscribe();
        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, subscribeOp, spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(1, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("Event"));

        JsonNode schema = operation.getResponseSchemas().get("Event");
        // The resolved schema must contain the actual type and properties from the component schema
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("id"));
        assertTrue(schema.get("properties").has("type"));
        assertTrue(schema.get("properties").has("data"));

        // Nested $ref to EventData must be resolved in definitions
        assertTrue(schema.has("definitions"));
        assertTrue(schema.get("definitions").has("EventData"));
        JsonNode eventDataDef = schema.get("definitions").get("EventData");
        assertEquals("object", eventDataDef.get("type").asText());
        assertTrue(eventDataDef.get("properties").has("key"));
    }

    @Test
    void getOperationObjectsFallsBackToDummy() {
        Channel emptyChannel = new Channel();
        List<OperationObject> ops = resolver.getOperationObjects(emptyChannel);

        assertEquals(1, ops.size());
        assertEquals("AMQP operation", ops.get(0).getSummary());
    }

    @Test
    void getSpecificationJsonNodeExtractsAmqpBindings() {
        Channel channel = new Channel();
        Map<String, Object> bindings = new LinkedHashMap<>();
        Map<String, Object> amqp = new LinkedHashMap<>();
        amqp.put("userId", "test-user");
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("name", "my-queue");
        amqp.put("queue", queue);
        Map<String, Object> exchange = new LinkedHashMap<>();
        exchange.put("name", "my-exchange");
        amqp.put("exchange", exchange);
        bindings.put("amqp", amqp);
        channel.setBindings(bindings);

        OperationObject op = new OperationObject();
        JsonNode node = resolver.getSpecificationJsonNode("notifications", channel, op);

        assertEquals("test-user", node.get("username").asText());
        assertEquals("my-queue", node.get("queue").asText());
        assertEquals("my-exchange", node.get("exchangeName").asText());
    }

    @Test
    void getSpecificationJsonNodeWithNoAmqpBindings() {
        Channel channel = new Channel();
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("kafka", Map.of("key", "value"));
        channel.setBindings(bindings);

        OperationObject op = new OperationObject();
        JsonNode node = resolver.getSpecificationJsonNode("test", channel, op);

        assertFalse(node.has("username"));
        assertFalse(node.has("queue"));
        assertFalse(node.has("exchangeName"));
    }

    @Test
    void getSpecificationJsonNodeWithPartialAmqpBindings() {
        Channel channel = new Channel();
        Map<String, Object> bindings = new LinkedHashMap<>();
        Map<String, Object> amqp = new LinkedHashMap<>();
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("name", "only-queue");
        amqp.put("queue", queue);
        // no userId, no exchange
        bindings.put("amqp", amqp);
        channel.setBindings(bindings);

        OperationObject op = new OperationObject();
        JsonNode node = resolver.getSpecificationJsonNode("test", channel, op);

        assertFalse(node.has("username"), "Missing userId should not produce username");
        assertEquals("only-queue", node.get("queue").asText());
        assertFalse(node.has("exchangeName"), "Missing exchange should not produce exchangeName");
    }

    @Test
    void getSpecificationJsonNodeWithNullBindings() {
        Channel channel = new Channel();
        channel.setBindings(null);

        OperationObject op = new OperationObject();
        // valueToTree(null) returns NullNode, path("amqp") returns MissingNode → no crash
        JsonNode node = resolver.getSpecificationJsonNode("test", channel, op);
        assertNotNull(node);
        assertFalse(node.has("username"));
    }

    private AsyncapiSpecification readYaml(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return yamlMapper.readValue(content, AsyncapiSpecification.class);
        }
    }
}
