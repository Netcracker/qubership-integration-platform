package org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Message;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSchemaResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KafkaSpecificationResolverTest {

    private KafkaSpecificationResolver resolver;
    private final YAMLMapper yamlMapper = new YAMLMapper();

    @BeforeEach
    void setUp() {
        resolver = new KafkaSpecificationResolver(new AsyncApiSchemaResolver());
    }

    @Test
    void setUpOperationMessagesWithInlineOneOf() throws IOException {
        AsyncapiSpecification spec = readYaml("asyncapi/v2/kafka-v2-inline-oneof.yaml");
        Channel channel = spec.getChannels().get("chat.presence");
        OperationObject operationObject = channel.getPublish();

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, operationObject, spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(2, operation.getResponseSchemas().size(),
                "Two inline oneOf messages should produce two response schema entries");

        // Keys should be payload_0 and payload_1 for unnamed inline messages
        assertTrue(operation.getResponseSchemas().containsKey("payload_0"));
        assertTrue(operation.getResponseSchemas().containsKey("payload_1"));

        // Each entry should have full schema: type, properties, required, const
        JsonNode first = operation.getResponseSchemas().get("payload_0");
        assertEquals("object", first.get("type").asText());
        assertTrue(first.has("properties"));
        assertTrue(first.get("properties").has("userId"));
        assertTrue(first.has("required"), "required fields must be preserved");
        assertEquals(3, first.get("required").size());

        // const on nested property must be preserved
        JsonNode typeProperty = first.get("properties").get("type");
        assertEquals("online", typeProperty.get("const").asText());

        JsonNode second = operation.getResponseSchemas().get("payload_1");
        assertEquals("object", second.get("type").asText());
        assertTrue(second.has("properties"));
        assertTrue(second.get("properties").has("lastSeenAt"));
        assertTrue(second.has("required"), "required fields must be preserved");

        // format on nested property must be preserved
        JsonNode lastSeenNode = second.get("properties").get("lastSeenAt");
        assertEquals("date-time", lastSeenNode.get("format").asText());
    }

    @Test
    void setUpOperationMessagesWithInlinePayload() throws IOException {
        String yaml = """
                asyncapi: 2.6.0
                info:
                  title: Test
                  version: 1.0.0
                servers:
                  prod:
                    url: kafka:9092
                    protocol: kafka
                channels:
                  test.topic:
                    publish:
                      operationId: testOp
                      message:
                        payload:
                          type: object
                          properties:
                            name:
                              type: string
                            age:
                              type: integer
                """;

        AsyncapiSpecification spec = yamlMapper.readValue(yaml, AsyncapiSpecification.class);
        Channel channel = spec.getChannels().get("test.topic");
        OperationObject operationObject = channel.getPublish();

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, operationObject, spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(1, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("payload"));

        JsonNode payload = operation.getResponseSchemas().get("payload");
        assertEquals("object", payload.get("type").asText());
        assertTrue(payload.get("properties").has("name"));
        assertTrue(payload.get("properties").has("age"));
    }

    @Test
    void setUpOperationMessagesWithRefOneOf() throws IOException {
        String yaml = """
                asyncapi: 2.6.0
                info:
                  title: Test
                  version: 1.0.0
                servers:
                  prod:
                    url: kafka:9092
                    protocol: kafka
                channels:
                  test.topic:
                    publish:
                      operationId: testOp
                      message:
                        oneOf:
                          - $ref: '#/components/messages/MsgA'
                          - $ref: '#/components/messages/MsgB'
                components:
                  messages:
                    MsgA:
                      payload:
                        type: object
                        properties:
                          fieldA:
                            type: string
                    MsgB:
                      payload:
                        type: object
                        properties:
                          fieldB:
                            type: integer
                """;

        AsyncapiSpecification spec = yamlMapper.readValue(yaml, AsyncapiSpecification.class);
        Channel channel = spec.getChannels().get("test.topic");
        OperationObject operationObject = channel.getPublish();

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, operationObject, spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(2, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("MsgA"));
        assertTrue(operation.getResponseSchemas().containsKey("MsgB"));
    }

    @Test
    void setUpOperationMessagesWithMixedOneOf() throws IOException {
        String yaml = """
                asyncapi: 2.6.0
                info:
                  title: Test
                  version: 1.0.0
                channels:
                  events:
                    publish:
                      operationId: sendEvent
                      message:
                        oneOf:
                          - $ref: '#/components/messages/Created'
                          - payload:
                              type: object
                              required: [reason]
                              properties:
                                reason:
                                  type: string
                                code:
                                  type: integer
                components:
                  messages:
                    Created:
                      payload:
                        type: object
                        properties:
                          id:
                            type: string
                """;

        AsyncapiSpecification spec = yamlMapper.readValue(yaml, AsyncapiSpecification.class);
        Channel channel = spec.getChannels().get("events");

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, channel.getPublish(), spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(2, operation.getResponseSchemas().size());

        // $ref entry resolved by name
        assertTrue(operation.getResponseSchemas().containsKey("Created"));

        // inline entry gets indexed key
        assertTrue(operation.getResponseSchemas().containsKey("payload_1"));
        JsonNode inline = operation.getResponseSchemas().get("payload_1");
        assertEquals("object", inline.get("type").asText());
        assertTrue(inline.has("required"));
        assertTrue(inline.get("properties").has("reason"));
        assertTrue(inline.get("properties").has("code"));
    }

    @Test
    void setUpOperationMessagesWithAllOf() throws IOException {
        String yaml = """
                asyncapi: 2.6.0
                info:
                  title: Test
                  version: 1.0.0
                channels:
                  composite:
                    subscribe:
                      operationId: receiveComposite
                      message:
                        allOf:
                          - $ref: '#/components/messages/Base'
                          - $ref: '#/components/messages/Extended'
                components:
                  messages:
                    Base:
                      payload:
                        type: object
                        properties:
                          id:
                            type: string
                    Extended:
                      payload:
                        type: object
                        properties:
                          detail:
                            type: string
                """;

        AsyncapiSpecification spec = yamlMapper.readValue(yaml, AsyncapiSpecification.class);
        Channel channel = spec.getChannels().get("composite");

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, channel.getSubscribe(), spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(2, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("Base"));
        assertTrue(operation.getResponseSchemas().containsKey("Extended"));
    }

    @Test
    void setUpOperationMessagesWithAnyOf() throws IOException {
        String yaml = """
                asyncapi: 2.6.0
                info:
                  title: Test
                  version: 1.0.0
                channels:
                  flexible:
                    publish:
                      operationId: sendFlexible
                      message:
                        anyOf:
                          - payload:
                              type: object
                              properties:
                                text:
                                  type: string
                          - payload:
                              type: object
                              properties:
                                data:
                                  type: number
                """;

        AsyncapiSpecification spec = yamlMapper.readValue(yaml, AsyncapiSpecification.class);
        Channel channel = spec.getChannels().get("flexible");

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, channel.getPublish(), spec.getComponents());

        assertNotNull(operation.getResponseSchemas());
        assertEquals(2, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("payload_0"));
        assertTrue(operation.getResponseSchemas().containsKey("payload_1"));

        JsonNode first = operation.getResponseSchemas().get("payload_0");
        assertTrue(first.get("properties").has("text"));
        JsonNode second = operation.getResponseSchemas().get("payload_1");
        assertTrue(second.get("properties").has("data"));
    }

    @Test
    void setUpOperationMessagesWithNullComponents() {
        OperationObject operationObject = new OperationObject();
        Message message = new Message();
        message.setPayload(new org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.SchemaObject());
        message.getPayload().setType("object");
        operationObject.setMessage(message);

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, operationObject, null);

        assertNotNull(operation.getResponseSchemas());
        assertEquals(1, operation.getResponseSchemas().size());
        assertTrue(operation.getResponseSchemas().containsKey("payload"));
    }

    @Test
    void setUpOperationMessagesWithEmptyMessage() {
        OperationObject operationObject = new OperationObject();
        operationObject.setMessage(new Message());

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, operationObject, null);

        assertNotNull(operation.getResponseSchemas());
        assertTrue(operation.getResponseSchemas().isEmpty());
    }

    @Test
    void setUpOperationMessagesWithNoMessage() {
        OperationObject operationObject = new OperationObject();

        Operation operation = Operation.builder().build();
        resolver.setUpOperationMessages(operation, operationObject, null);

        assertNotNull(operation.getResponseSchemas());
        assertTrue(operation.getResponseSchemas().isEmpty());
    }

    @Test
    void getOperationObjectsWithPublishOnly() {
        Channel channel = new Channel();
        OperationObject pub = new OperationObject();
        pub.setOperationId("pubOp");
        channel.setPublish(pub);

        List<OperationObject> ops = resolver.getOperationObjects(channel);
        assertEquals(1, ops.size());
        assertEquals("pubOp", ops.get(0).getOperationId());
    }

    @Test
    void getOperationObjectsWithSubscribeOnly() {
        Channel channel = new Channel();
        OperationObject sub = new OperationObject();
        sub.setOperationId("subOp");
        channel.setSubscribe(sub);

        List<OperationObject> ops = resolver.getOperationObjects(channel);
        assertEquals(1, ops.size());
        assertEquals("subOp", ops.get(0).getOperationId());
    }

    @Test
    void getOperationObjectsWithBoth() {
        Channel channel = new Channel();
        OperationObject pub = new OperationObject();
        pub.setOperationId("pubOp");
        OperationObject sub = new OperationObject();
        sub.setOperationId("subOp");
        channel.setPublish(pub);
        channel.setSubscribe(sub);

        List<OperationObject> ops = resolver.getOperationObjects(channel);
        assertEquals(2, ops.size());
    }

    @Test
    void getOperationObjectsWithEmptyChannel() {
        Channel channel = new Channel();
        List<OperationObject> ops = resolver.getOperationObjects(channel);
        assertTrue(ops.isEmpty());
    }

    @Test
    void getSpecificationJsonNodeWithTopic() {
        Channel channel = new Channel();
        OperationObject op = new OperationObject();

        JsonNode node = resolver.getSpecificationJsonNode("my.topic", channel, op);
        assertEquals("my.topic", node.get("topic").asText());
        assertFalse(node.has("maasClassifierName"));
    }

    @Test
    void getSpecificationJsonNodeWithMaasClassifier() {
        Channel channel = new Channel();
        OperationObject op = new OperationObject();
        op.setMaasClassifierName("my-classifier");

        JsonNode node = resolver.getSpecificationJsonNode("my.topic", channel, op);
        assertEquals("my.topic", node.get("topic").asText());
        assertEquals("my-classifier", node.get("maasClassifierName").asText());
    }

    private AsyncapiSpecification readYaml(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return yamlMapper.readValue(content, AsyncapiSpecification.class);
        }
    }
}
