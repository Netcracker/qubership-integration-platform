package org.qubership.integration.platform.runtime.catalog.service.parsers.asyncapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Message;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.OperationObject;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components.Components;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3.*;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl.AMQPSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl.KafkaSpecificationResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsyncApiV3NormalizerTest {

    private AsyncApiV3Normalizer normalizer;
    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        normalizer = new AsyncApiV3Normalizer(new ObjectMapper());
    }

    @Test
    void normalizeKafkaSimple() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-simple.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertNotNull(v2.getInfo());
        assertEquals("Kafka Simple API", v2.getInfo().getTitle());
        assertEquals("1.0.0", v2.getInfo().getVersion());

        assertNotNull(v2.getServers());
        assertEquals(1, v2.getServers().size());
        assertEquals("kafka.example.com:9092", v2.getServers().get("production").getUrl());
        assertEquals("kafka", v2.getServers().get("production").getProtocol());

        assertNotNull(v2.getChannels());
        assertTrue(v2.getChannels().containsKey("user/signedup"));
        Channel channel = v2.getChannels().get("user/signedup");
        assertNotNull(channel.getPublish());
        assertEquals("publishUserSignedUp", channel.getPublish().getOperationId());
        assertNull(channel.getSubscribe());
    }

    @Test
    void normalizeMultiOperation() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-multi-operation.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertTrue(v2.getChannels().containsKey("user/events"));
        Channel channel = v2.getChannels().get("user/events");
        assertNotNull(channel.getPublish());
        assertNotNull(channel.getSubscribe());
        assertEquals("publishUserEvent", channel.getPublish().getOperationId());
        assertEquals("consumeUserEvent", channel.getSubscribe().getOperationId());
    }

    @Test
    void normalizeWithComponentRefs() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-with-refs.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertTrue(v2.getChannels().containsKey("order/created"));
        Channel channel = v2.getChannels().get("order/created");
        assertNotNull(channel.getPublish());
        assertNotNull(channel.getPublish().getMessage());

        assertNotNull(v2.getComponents());
        assertNotNull(v2.getComponents().getMessages());
        assertTrue(v2.getComponents().getMessages().containsKey("OrderCreated"));
        assertNotNull(v2.getComponents().getSchemas());
        assertTrue(v2.getComponents().getSchemas().containsKey("OrderId"));
    }

    @Test
    void normalizeRequestReply() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-request-reply.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertTrue(v2.getChannels().containsKey("order/request"));
        Channel requestChannel = v2.getChannels().get("order/request");
        assertNotNull(requestChannel.getPublish());
        assertEquals("sendOrderRequest", requestChannel.getPublish().getOperationId());

        assertTrue(v2.getChannels().containsKey("order/reply"));
        Channel replyChannel = v2.getChannels().get("order/reply");
        assertNotNull(replyChannel.getSubscribe());
        assertEquals("sendOrderRequestReply", replyChannel.getSubscribe().getOperationId());
        assertNotNull(replyChannel.getSubscribe().getMessage());
    }

    @Test
    void normalizeAmqpSimple() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/amqp-v3-simple.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertNotNull(v2.getServers());
        assertEquals("rabbitmq.example.com:5672/vhost",
                v2.getServers().get("production").getUrl());
        assertEquals("amqp", v2.getServers().get("production").getProtocol());

        assertTrue(v2.getChannels().containsKey("notifications"));
        Channel channel = v2.getChannels().get("notifications");
        assertNotNull(channel.getSubscribe());
        assertEquals("consumeNotification", channel.getSubscribe().getOperationId());
        assertNotNull(channel.getBindings());
        assertEquals("Notification queue", channel.getDescription());
    }

    @Test
    void normalizeWithNoServers() throws IOException {
        AsyncapiV3Specification v3 = readJsonV3("asyncapi/v3/kafka-v3-no-servers.json");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertNotNull(v2.getServers());
        assertTrue(v2.getServers().isEmpty());
        assertNotNull(v2.getInfo());
        assertEquals("kafka", v2.getInfo().getProtocol());

        assertTrue(v2.getChannels().containsKey("events/all"));
    }

    @Test
    void normalizeInlineChannelMessageIsResolved() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-simple.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        Channel channel = v2.getChannels().get("user/signedup");
        Message message = channel.getPublish().getMessage();
        assertNotNull(message);
        assertNull(message.get$ref(), "Inline channel message should be resolved, not left as $ref");
        assertNotNull(message.getPayload(), "Payload should be extracted from inline channel message");
        assertEquals("UserSignedUp", message.getName());
    }

    @Test
    void normalizeComponentRefIsPreserved() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-with-refs.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        Channel channel = v2.getChannels().get("order/created");
        Message message = channel.getPublish().getMessage();
        assertNotNull(message);
        assertEquals("#/components/messages/OrderCreated", message.get$ref(),
                "Component $ref should be preserved as-is");
    }

    @Test
    void normalizePreservesV3ActionOnOperations() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-multi-operation.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        Channel channel = v2.getChannels().get("user/events");
        assertNotNull(channel.getPublish());
        assertEquals("send", channel.getPublish().getAction());
        assertNotNull(channel.getSubscribe());
        assertEquals("receive", channel.getSubscribe().getAction());
    }

    @Test
    void kafkaResolverReturnsV3ActionWhenPresent() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-multi-operation.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);
        KafkaSpecificationResolver resolver = new KafkaSpecificationResolver(null);

        Channel channel = v2.getChannels().get("user/events");
        assertEquals("send", resolver.getMethod(channel, channel.getPublish()));
        assertEquals("receive", resolver.getMethod(channel, channel.getSubscribe()));
    }

    @Test
    void kafkaResolverFallsBackForV2Operations() {
        KafkaSpecificationResolver resolver = new KafkaSpecificationResolver(null);
        Channel channel = new Channel();
        OperationObject pub = new OperationObject();
        channel.setPublish(pub);

        assertEquals("publish", resolver.getMethod(channel, pub));

        OperationObject sub = new OperationObject();
        channel.setSubscribe(sub);
        assertEquals("subscribe", resolver.getMethod(channel, sub));
    }

    @Test
    void amqpResolverReturnsV3ActionWhenPresent() {
        AMQPSpecificationResolver resolver = new AMQPSpecificationResolver(null);
        Channel channel = new Channel();
        OperationObject op = new OperationObject();
        op.setAction("send");
        channel.setPublish(op);

        assertEquals("send", resolver.getMethod(channel, op));
    }

    @Test
    void amqpResolverFallsBackForV2Operations() {
        AMQPSpecificationResolver resolver = new AMQPSpecificationResolver(null);
        Channel channel = new Channel();
        OperationObject pub = new OperationObject();
        channel.setPublish(pub);

        assertEquals("subscribe", resolver.getMethod(channel, pub));

        OperationObject sub = new OperationObject();
        channel.setSubscribe(sub);
        assertEquals("publish", resolver.getMethod(channel, sub));
    }

    @Test
    void normalizeKafkaComplexSchemas() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-complex-schemas.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertEquals(2, v2.getServers().size());
        assertNotNull(v2.getServers().get("production"));
        assertNotNull(v2.getServers().get("staging"));

        Map<String, Channel> channels = v2.getChannels();
        assertEquals(4, channels.size());
        assertTrue(channels.containsKey("orders.commands"));
        assertTrue(channels.containsKey("orders.events"));
        assertTrue(channels.containsKey("orders.cancellations"));
        assertTrue(channels.containsKey("inventory.stock.updates"));

        // createOrder → send → publish on orders.commands
        Channel commandsChannel = channels.get("orders.commands");
        assertNotNull(commandsChannel.getPublish());
        assertEquals("createOrder", commandsChannel.getPublish().getOperationId());
        Message createOrderMsg = commandsChannel.getPublish().getMessage();
        assertNotNull(createOrderMsg);
        assertEquals("#/components/messages/CreateOrder", createOrderMsg.get$ref());

        // onOrderCancelled → receive → subscribe on orders.cancellations (inline message)
        Channel cancellationsChannel = channels.get("orders.cancellations");
        assertNotNull(cancellationsChannel.getSubscribe());
        assertEquals("onOrderCancelled", cancellationsChannel.getSubscribe().getOperationId());
        Message cancelledMsg = cancellationsChannel.getSubscribe().getMessage();
        assertNotNull(cancelledMsg);
        assertNotNull(cancelledMsg.getPayload());
        assertNotNull(cancelledMsg.getPayload().getRequired());
        assertTrue(cancelledMsg.getPayload().getRequired().contains("orderId"));
        assertTrue(cancelledMsg.getPayload().getRequired().contains("reason"));
        assertNotNull(cancelledMsg.getPayload().getProperties().get("reason"),
                "reason property with enum should be preserved");

        // inventoryUpdates — inline message with additionalProperties on nested metadata
        Channel inventoryChannel = channels.get("inventory.stock.updates");
        assertNotNull(inventoryChannel.getSubscribe());
        Message stockMsg = inventoryChannel.getSubscribe().getMessage();
        assertNotNull(stockMsg);
        assertNotNull(stockMsg.getPayload());
        assertNotNull(stockMsg.getPayload().getRequired());
        assertTrue(stockMsg.getPayload().getRequired().contains("sku"));
        assertNotNull(stockMsg.getPayload().getProperties().get("metadata"),
                "metadata property with additionalProperties should be preserved");

        // components: schemas with nested refs, required, constraints
        Components components = v2.getComponents();
        assertNotNull(components);
        assertNotNull(components.getMessages());
        assertTrue(components.getMessages().containsKey("CreateOrder"));
        assertTrue(components.getMessages().containsKey("OrderCreated"));
        assertNotNull(components.getSchemas());
        assertTrue(components.getSchemas().containsKey("OrderItem"));
        assertTrue(components.getSchemas().containsKey("Address"));
        assertTrue(components.getSchemas().containsKey("PercentageDiscount"));
        assertTrue(components.getSchemas().containsKey("FixedDiscount"));

        // Verify schema constraints are preserved
        var orderItemSchema = components.getSchemas().get("OrderItem");
        assertNotNull(orderItemSchema.getRequired());
        assertEquals(3, orderItemSchema.getRequired().size());
        assertTrue(orderItemSchema.getRequired().contains("sku"));
        assertTrue(orderItemSchema.getRequired().contains("quantity"));
        assertTrue(orderItemSchema.getRequired().contains("unitPrice"));
        assertNotNull(orderItemSchema.getProperties());
        assertTrue(orderItemSchema.getProperties().containsKey("sku"));
        assertTrue(orderItemSchema.getProperties().containsKey("quantity"));

        var addressSchema = components.getSchemas().get("Address");
        assertNotNull(addressSchema.getRequired());
        assertTrue(addressSchema.getRequired().contains("street"));
        assertTrue(addressSchema.getRequired().contains("city"));
        assertTrue(addressSchema.getRequired().contains("country"));
    }

    @Test
    void normalizeAmqpExchangeBindings() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/amqp-v3-exchange-bindings.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertEquals(2, v2.getServers().size());
        assertEquals("rabbitmq.prod.internal:5672/notifications",
                v2.getServers().get("production").getUrl());
        assertEquals("rabbitmq-failover.prod.internal:5672/notifications",
                v2.getServers().get("failover").getUrl());

        Map<String, Channel> channels = v2.getChannels();
        assertEquals(3, channels.size());
        assertTrue(channels.containsKey("notifications.email"));
        assertTrue(channels.containsKey("notifications.sms"));
        assertTrue(channels.containsKey("notifications.dlq"));

        // Email queue bindings preserved
        Channel emailChannel = channels.get("notifications.email");
        assertNotNull(emailChannel.getBindings());
        @SuppressWarnings("unchecked")
        Map<String, Object> amqpBindings = (Map<String, Object>) emailChannel.getBindings().get("amqp");
        assertNotNull(amqpBindings);
        assertEquals("queue", amqpBindings.get("is"));
        @SuppressWarnings("unchecked")
        Map<String, Object> queueBinding = (Map<String, Object>) amqpBindings.get("queue");
        assertEquals("notifications.email", queueBinding.get("name"));
        assertEquals(true, queueBinding.get("durable"));
        @SuppressWarnings("unchecked")
        Map<String, Object> exchangeBinding = (Map<String, Object>) amqpBindings.get("exchange");
        assertEquals("notifications.topic", exchangeBinding.get("name"));
        assertEquals("topic", exchangeBinding.get("type"));

        // sendEmail → publish, consumeEmail → subscribe on same channel
        assertNotNull(emailChannel.getPublish());
        assertNotNull(emailChannel.getSubscribe());
        assertEquals("sendEmail", emailChannel.getPublish().getOperationId());
        assertEquals("consumeEmail", emailChannel.getSubscribe().getOperationId());

        // Email message — inline with complex nested structure
        Message emailMsg = emailChannel.getSubscribe().getMessage();
        assertNotNull(emailMsg);
        assertNotNull(emailMsg.getPayload());
        assertNotNull(emailMsg.getPayload().getRequired());
        assertTrue(emailMsg.getPayload().getRequired().contains("to"));
        assertTrue(emailMsg.getPayload().getRequired().contains("subject"));
        assertTrue(emailMsg.getPayload().getRequired().contains("templateId"));

        // DLQ channel bindings — fanout exchange
        Channel dlqChannel = channels.get("notifications.dlq");
        assertNotNull(dlqChannel.getBindings());
        assertNotNull(dlqChannel.getSubscribe());
        assertEquals("processDeadLetters", dlqChannel.getSubscribe().getOperationId());
    }

    @Test
    void normalizeEmptyOperations() {
        AsyncapiV3Specification v3 = new AsyncapiV3Specification();
        v3.setAsyncapi("3.0.0");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertNotNull(v2.getChannels());
        assertTrue(v2.getChannels().isEmpty());
    }

    @Test
    void normalizeMultipleOperationMessagesProducesOneOf() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-multi-message.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        Channel channel = v2.getChannels().get("events/all");
        assertNotNull(channel);
        assertNotNull(channel.getPublish());
        Message msg = channel.getPublish().getMessage();
        assertNotNull(msg);
        assertNotNull(msg.getOneOf(), "Multiple operation messages should produce oneOf");
        assertEquals(2, msg.getOneOf().size());
    }

    @Test
    void normalizeChannelMessagesWithoutExplicitOpMessagesProducesOneOf() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-multi-message.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // consumeFromChannel has no explicit messages → falls through to single channel message
        Channel channel = v2.getChannels().get("commands/process");
        assertNotNull(channel);
        assertNotNull(channel.getSubscribe());
        Message msg = channel.getSubscribe().getMessage();
        assertNotNull(msg);
        // Single channel message → direct message, not oneOf
        assertNull(msg.getOneOf());
        assertNotNull(msg.getPayload());
        assertEquals("DoSomething", msg.getName());
    }

    @Test
    void normalizeEdgeCaseMultipleChannelMessagesNoOpMessages() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-edge-cases.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // opWithChannelMessages references existingChannel with 2 messages but has no explicit op messages
        Channel channel = v2.getChannels().get("existing/topic");
        assertNotNull(channel);
        assertNotNull(channel.getSubscribe());
        Message msg = channel.getSubscribe().getMessage();
        assertNotNull(msg);
        assertNotNull(msg.getOneOf(), "2 channel messages with no op messages should produce oneOf");
        assertEquals(2, msg.getOneOf().size());
    }

    @Test
    void normalizeOperationWithMissingChannelSkips() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-edge-cases.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // opWithMissingChannel references nonExistentChannel — should be skipped
        // opWithNoChannelRef has no channel — should also be skipped
        // Only existingChannel-related operations should produce channels
        for (Channel ch : v2.getChannels().values()) {
            // Verify no operation produced from the missing channel or no-ref operation
            if (ch.getPublish() != null) {
                assertNotEquals("References missing channel", ch.getPublish().getSummary());
            }
            if (ch.getSubscribe() != null) {
                assertNotEquals("Has no channel reference at all", ch.getSubscribe().getSummary());
            }
        }
    }

    @Test
    void normalizeInlineMessageEntry() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-edge-cases.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // opWithInlineMessage has a single inline message (no $ref) with name and payload
        Channel channel = v2.getChannels().get("existing/topic");
        assertNotNull(channel);
        // This is the "send" operation on the same channel
        assertNotNull(channel.getPublish());
        Message msg = channel.getPublish().getMessage();
        assertNotNull(msg);
        assertEquals("InlineMsg", msg.getName());
        assertNotNull(msg.getPayload());
    }

    @Test
    void normalizeBareChannelRef() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-edge-cases.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // opWithBareRef has channel $ref = 'some-direct-ref' (not #/channels/ prefix)
        // resolveChannelKey should return 'some-direct-ref' directly, channel won't be found → skipped
        assertFalse(v2.getChannels().containsKey("some-direct-ref"),
                "Bare ref to non-existent channel should result in skipped operation");
    }

    @Test
    void normalizeNullInfo() {
        AsyncapiV3Specification v3 = new AsyncapiV3Specification();
        v3.setAsyncapi("3.0.0");
        v3.setInfo(null);
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertNotNull(v2.getInfo(), "Null info should produce empty Info object");
    }

    @Test
    void normalizeNullComponents() {
        AsyncapiV3Specification v3 = new AsyncapiV3Specification();
        v3.setAsyncapi("3.0.0");
        v3.setComponents(null);
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertNotNull(v2.getComponents(), "Null components should produce empty Components");
    }

    @Test
    void normalizeReplyWithChannelMessagesAndNoExplicitReplyMessages() throws IOException {
        // Build a v3 spec programmatically: reply has no messages, reply channel has messages
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-request-reply.yaml");

        // Modify to remove reply messages so it falls through to channel messages
        V3Operation sendOp = v3.getOperations().get("sendOrderRequest");
        sendOp.getReply().setMessages(null);

        AsyncapiSpecification v2 = normalizer.normalize(v3);

        assertTrue(v2.getChannels().containsKey("order/reply"));
        Channel replyChannel = v2.getChannels().get("order/reply");
        assertNotNull(replyChannel.getSubscribe());
        Message replyMsg = replyChannel.getSubscribe().getMessage();
        assertNotNull(replyMsg, "Reply should have message from channel when reply.messages is null");
    }

    @Test
    void normalizeReplyWithNullChannel() {
        AsyncapiV3Specification v3 = new AsyncapiV3Specification();
        v3.setAsyncapi("3.0.0");

        V3Channel channel = new V3Channel();
        channel.setAddress("test/addr");
        Map<String, V3Channel> channels = new LinkedHashMap<>();
        channels.put("testCh", channel);
        v3.setChannels(channels);

        V3Operation op = new V3Operation();
        op.setAction("send");
        V3ChannelRef chRef = new V3ChannelRef();
        chRef.setRef("#/channels/testCh");
        op.setChannel(chRef);
        // Reply with null channel
        V3Reply reply = new V3Reply();
        reply.setChannel(null);
        op.setReply(reply);

        Map<String, V3Operation> operations = new LinkedHashMap<>();
        operations.put("testOp", op);
        v3.setOperations(operations);

        AsyncapiSpecification v2 = normalizer.normalize(v3);
        // Should not crash; reply with null channel is just skipped
        assertTrue(v2.getChannels().containsKey("test/addr"));
    }

    @Test
    void normalizeChannelMessageWithRefToComponent() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-channel-ref-messages.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // consumeRefChannel: single channel message is $ref to component → convertChannelMessageEntry $ref branch
        Channel channel = v2.getChannels().get("events/ref");
        assertNotNull(channel);
        assertNotNull(channel.getSubscribe());
        Message msg = channel.getSubscribe().getMessage();
        assertNotNull(msg);
        assertEquals("#/components/messages/SharedEvent", msg.get$ref(),
                "Channel message $ref to component should be converted to v2 component ref");
    }

    @Test
    void normalizeMixedChannelMessagesOneOf() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-channel-ref-messages.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // consumeMixedChannel: 2 channel messages (1 inline + 1 $ref), no explicit op messages → oneOf via channelMessageToMap
        Channel channel = v2.getChannels().get("events/mixed");
        assertNotNull(channel);
        assertNotNull(channel.getSubscribe());
        Message msg = channel.getSubscribe().getMessage();
        assertNotNull(msg);
        assertNotNull(msg.getOneOf(), "Mixed channel messages should produce oneOf");
        assertEquals(2, msg.getOneOf().size());
    }

    @Test
    void normalizeMultipleOpMessagesWithChannelResolution() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-channel-ref-messages.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // sendWithMultipleChannelRefs: 2 messages referencing different channel messages → resolveMessageRef
        Channel channel = v2.getChannels().get("events/inline");
        assertNotNull(channel);
        assertNotNull(channel.getPublish());
        Message msg = channel.getPublish().getMessage();
        assertNotNull(msg);
        assertNotNull(msg.getOneOf(), "Multiple operation messages should produce oneOf");
        assertEquals(2, msg.getOneOf().size());

        // First message is resolved inline from inlineChannel (has payload)
        Map<String, Object> first = msg.getOneOf().get(0);
        assertTrue(first.containsKey("payload"), "Inline channel message should be resolved with payload");

        // Second message references mixedChannel message
        Map<String, Object> second = msg.getOneOf().get(1);
        assertTrue(second.containsKey("payload"), "Inline channel message from another channel should be resolved");
    }

    @Test
    void normalizeReceiveActionReplyBecomesPublish() throws IOException {
        AsyncapiV3Specification v3 = readYamlV3("asyncapi/v3/kafka-v3-channel-ref-messages.yaml");
        AsyncapiSpecification v2 = normalizer.normalize(v3);

        // receiveWithReply: action=receive → reply should become publish
        Channel replyChannel = v2.getChannels().get("reply/target");
        assertNotNull(replyChannel);
        assertNotNull(replyChannel.getPublish(), "Reply of receive action should become publish");
        assertNull(replyChannel.getSubscribe());
        assertEquals("receiveWithReplyReply", replyChannel.getPublish().getOperationId());
    }

    @Test
    void normalizeConvertComponentRefPreservesAlreadyComponentRef() {
        // Test convertComponentRef indirectly via a spec where operation message is $ref to component
        AsyncapiV3Specification v3 = new AsyncapiV3Specification();
        v3.setAsyncapi("3.0.0");

        V3Channel channel = new V3Channel();
        channel.setAddress("test/addr");
        Map<String, V3Channel> channels = new LinkedHashMap<>();
        channels.put("testCh", channel);
        v3.setChannels(channels);

        V3Operation op = new V3Operation();
        op.setAction("send");
        V3ChannelRef chRef = new V3ChannelRef();
        chRef.setRef("#/channels/testCh");
        op.setChannel(chRef);
        // Single message with $ref to component (not channel) — should stay as-is
        op.setMessages(List.of(Map.of("$ref", "#/components/messages/Foo")));

        Map<String, V3Operation> operations = new LinkedHashMap<>();
        operations.put("testOp", op);
        v3.setOperations(operations);

        AsyncapiSpecification v2 = normalizer.normalize(v3);
        Channel v2Channel = v2.getChannels().get("test/addr");
        assertNotNull(v2Channel.getPublish());
        Message msg = v2Channel.getPublish().getMessage();
        assertEquals("#/components/messages/Foo", msg.get$ref());
    }

    private AsyncapiV3Specification readYamlV3(String path) throws IOException {
        String content = readResource(path);
        return yamlMapper.readValue(content, AsyncapiV3Specification.class);
    }

    private AsyncapiV3Specification readJsonV3(String path) throws IOException {
        String content = readResource(path);
        return jsonMapper.readValue(content, AsyncapiV3Specification.class);
    }

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
