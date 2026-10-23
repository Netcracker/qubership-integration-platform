package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.jms.JmsBinding;
import org.apache.camel.component.jms.JmsMessage;
import org.apache.camel.component.jms.StreamMessageInputStream;
import org.apache.camel.support.DefaultExchange;
import org.apache.qpid.jms.message.JmsStreamMessage;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotFailureAssertions;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class JmsTriggerSnapshotRuntime implements AutoCloseable {
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper();
    private static final ObjectMapper EXPECTATION_MAPPER = ObjectMappers.getObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private Connection connection;
    private Session session;
    private Destination destination;
    private MessageProducer producer;
    private final String destinationName;
    private final boolean topic;
    private final BlockingQueue<Exchange> completed = new LinkedBlockingQueue<>();
    private final List<Exchange> received = new CopyOnWriteArrayList<>();
    private final Set<String> replyQueues = new LinkedHashSet<>();
    private final List<String> publishedIds = new ArrayList<>();
    private final List<String> invocationPublishedIds = new ArrayList<>();
    private final List<Exchange> invocationCompletions = new CopyOnWriteArrayList<>();
    private final List<SessionObservation> sessions = new CopyOnWriteArrayList<>();
    private List<JmsTriggerSnapshotRuntime> recipients = List.of(this);
    private int collectedCompletions;
    private int redeliveryCount;
    private boolean checkDeadLetters;
    private final Map<String, Map<?, ?>> publishedProperties = new LinkedHashMap<>();
    private final Set<String> expiredIds = new LinkedHashSet<>();
    private final List<String> receivedIds = new CopyOnWriteArrayList<>();
    private final List<String> completedIds = new CopyOnWriteArrayList<>();
    private AfterPublish afterPublish = messages -> { };

    @FunctionalInterface
    interface AfterPublish {
        void accept(List<PublishedMessage> messages) throws Exception;
    }

    record PublishedMessage(String id, long expiration) {
    }

    private record SessionObservation(int acknowledgmentMode, boolean transacted) {
    }

    JmsTriggerSnapshotRuntime(Connection connection, String destinationName) throws Exception {
        this(connection, destinationName, false);
    }

    JmsTriggerSnapshotRuntime(Connection connection, String destinationName, boolean topic) throws Exception {
        this.destinationName = destinationName;
        this.topic = topic;
        connect(connection);
    }

    void recipients(List<JmsTriggerSnapshotRuntime> recipients) {
        this.recipients = List.copyOf(recipients);
    }

    boolean sharesTopic(JmsTriggerSnapshotRuntime other) {
        return topic && other.topic && destinationName.equals(other.destinationName);
    }

    boolean isTopic(String name) {
        return topic && destinationName.equals(name);
    }

    void enableDeadLetterChecks() {
        checkDeadLetters = true;
    }

    void connect(Connection connection) throws Exception {
        this.connection = connection;
        session = connection.createSession(Session.AUTO_ACKNOWLEDGE);
        destination = topic ? session.createTopic(destinationName) : session.createQueue(destinationName);
        producer = session.createProducer(destination);
        connection.start();
    }

    public static String registryName(String nodeId) {
        return "snapshot-jms-trigger:" + nodeId;
    }

    public Exchange send(SnapshotScenarioInvocation invocation, Map<String, Object> parameters) throws Exception {
        List<PublishedMessage> published = new ArrayList<>();
        boolean batch = parameters.containsKey("messages");
        if (batch) {
            for (Object item : (List<?>) parameters.get("messages")) {
                Map<?, ?> message = (Map<?, ?>) item;
                Map<?, ?> headers = message.get("headers") instanceof Map<?, ?> values ? values : Map.of();
                published.add(publish(message.get("body"), headers, message));
            }
        } else {
            published.add(publish(invocation.getBody(), invocation.getHeaders(), parameters));
        }
        afterPublish.accept(List.copyOf(published));
        List<Exchange> results = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        for (PublishedMessage message : published) {
            if (expiredIds.contains(message.id())) {
                continue;
            }
            Exchange result = null;
            for (int delivery = 0; delivery <= redeliveryCount; delivery++) {
                result = collectCompletion(message.id(), invocation.getId(), deadline);
            }
            results.add(result);
        }
        assertFalse(results.isEmpty(), "JMS trigger invocation must include a message that completes the route.");
        if (!batch) {
            return results.getFirst();
        }
        Exchange result = copy(results.getLast());
        result.getMessage().setBody(results.stream().map(exchange -> exchange.getMessage().getBody()).toList());
        results.stream().filter(exchange -> exchange.getException() != null).findFirst()
                .ifPresent(failed -> result.setException(failed.getException()));
        return result;
    }

    private PublishedMessage publish(Object body, Map<?, ?> properties, Map<?, ?> parameters) throws Exception {
        String type = parameters.containsKey("messageType") ? parameters.get("messageType").toString() : "Text";
        Message message = createMessage(session, type, body);
        for (var header : properties.entrySet()) {
            message.setObjectProperty((String) header.getKey(), header.getValue());
        }
        Map<?, ?> headers = parameters.get("jmsHeaders") instanceof Map<?, ?> values ? values : Map.of();
        if (headers.containsKey("correlationId")) {
            message.setJMSCorrelationID((String) headers.get("correlationId"));
        }
        if (headers.containsKey("type")) {
            message.setJMSType((String) headers.get("type"));
        }
        if (headers.containsKey("replyTo")) {
            String replyQueue = (String) headers.get("replyTo");
            replyQueues.add(replyQueue);
            message.setJMSReplyTo(session.createQueue(replyQueue));
        }
        int deliveryMode = "NON_PERSISTENT".equals(headers.get("deliveryMode"))
                ? DeliveryMode.NON_PERSISTENT : DeliveryMode.PERSISTENT;
        int priority = headers.containsKey("priority") ? ((Number) headers.get("priority")).intValue() : 4;
        long timeToLive = headers.containsKey("timeToLiveMillis") ? ((Number) headers.get("timeToLiveMillis")).longValue() : 0;
        producer.send(message, deliveryMode, priority, timeToLive);
        PublishedMessage published = new PublishedMessage(message.getJMSMessageID(), message.getJMSExpiration());
        for (JmsTriggerSnapshotRuntime recipient : recipients) {
            recipient.publishedProperties.put(published.id(), new LinkedHashMap<>(properties));
            for (int delivery = 0; delivery <= recipient.redeliveryCount; delivery++) {
                recipient.publishedIds.add(published.id());
                recipient.invocationPublishedIds.add(published.id());
            }
        }
        return published;
    }

    void afterPublish(AfterPublish callback) {
        afterPublish = callback;
    }

    void assertNothingDelivered() {
        assertTrue(received.isEmpty(), "JMS trigger received a message while its consumer was stopped.");
        assertTrue(completed.isEmpty(), "JMS trigger completed a message while its consumer was stopped.");
    }

    void markExpired(List<PublishedMessage> messages) {
        assertNothingDelivered();
        for (PublishedMessage message : messages) {
            if (message.expiration() > 0) {
                assertTrue(message.expiration() <= System.currentTimeMillis(), "JMS message has not expired yet.");
                expiredIds.add(message.id());
            }
        }
        assertTrue(messages.stream().anyMatch(message -> message.expiration() == 0),
                "Expiration verification requires a fresh message that completes the route.");
    }

    static Message createMessage(Session session, String type, Object body) throws Exception {
        return switch (type) {
            case "Text" -> session.createTextMessage(body == null || body instanceof String
                    ? (String) body : MAPPER.writeValueAsString(body));
            case "Bytes" -> {
                BytesMessage message = session.createBytesMessage();
                if (body != null) {
                    message.writeBytes(body instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : bytes(body));
                }
                yield message;
            }
            case "Stream" -> {
                StreamMessage message = session.createStreamMessage();
                if (body instanceof List<?> chunks && !chunks.isEmpty() && chunks.getFirst() instanceof List<?>) {
                    for (Object chunk : chunks) {
                        message.writeBytes(bytes(chunk));
                    }
                } else if (body != null) {
                    message.writeBytes(body instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : bytes(body));
                }
                yield message;
            }
            case "Map" -> {
                MapMessage message = session.createMapMessage();
                if (body != null) {
                    if (!(body instanceof Map<?, ?> values)) {
                        throw new IllegalArgumentException("JMS Map input must be a map or null.");
                    }
                    for (var entry : values.entrySet()) {
                        message.setObject((String) entry.getKey(), entry.getValue());
                    }
                }
                yield message;
            }
            case "Object" -> session.createObjectMessage((Serializable) body);
            case "Message" -> {
                if (body != null) {
                    throw new IllegalArgumentException("JMS Message input cannot define a body.");
                }
                yield session.createMessage();
            }
            default -> throw new IllegalArgumentException("Unsupported JMS messageType '" + type + "'.");
        };
    }

    static byte[] bytes(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("JMS bytes must be a list of integers from 0 to 255.");
        }
        byte[] bytes = new byte[values.size()];
        for (int index = 0; index < values.size(); index++) {
            Object item = values.get(index);
            if (!(item instanceof Integer number) || number < 0 || number > 255) {
                throw new IllegalArgumentException("JMS bytes must be a list of integers from 0 to 255.");
            }
            bytes[index] = (byte) number.intValue();
        }
        return bytes;
    }

    void received(Exchange exchange) throws Exception {
        assertEquals(ExchangePattern.InOnly, exchange.getPattern(), "JMS trigger must consume messages as InOnly.");
        Exchange snapshot = copy(exchange);
        if (exchange.getIn() instanceof JmsMessage message) {
            Session consumerSession = message.getJmsSession();
            assertNotNull(consumerSession, "JMS trigger must expose the actual consumer session.");
            sessions.add(new SessionObservation(consumerSession.getAcknowledgeMode(), consumerSession.getTransacted()));
        }
        if (exchange.getMessage().getBody() instanceof StreamMessageInputStream) {
            JmsMessage message = assertInstanceOf(JmsMessage.class, exchange.getIn());
            JmsStreamMessage stream = assertInstanceOf(JmsStreamMessage.class, message.getJmsMessage());
            try (InputStream input = new StreamMessageInputStream(stream.copy());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
                snapshot.getMessage().setBody(new ByteArrayInputStream(output.toByteArray()));
            }
        }
        received.add(snapshot);
        receivedIds.add(exchange.getMessage().getHeader("JMSMessageID", String.class));
    }

    void completed(Exchange exchange) {
        Exchange snapshot = copy(exchange);
        if (snapshot.getException() == null && !exchange.getExchangeExtension().isFailureHandled()) {
            snapshot.setException(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class));
        }
        invocationCompletions.add(snapshot);
        completedIds.add(exchange.getMessage().getHeader("JMSMessageID", String.class));
        completed.add(snapshot);
    }

    private static Exchange copy(Exchange exchange) {
        Exchange copy = new DefaultExchange(exchange.getContext(), exchange.getPattern());
        Object body = exchange.getMessage().getBody();
        copy.getMessage().setBody(body instanceof byte[] bytes ? bytes.clone()
                : body instanceof Map<?, ?> map ? new LinkedHashMap<>(map) : body);
        copy.getMessage().setHeaders(new LinkedHashMap<>(exchange.getMessage().getHeaders()));
        exchange.getAllProperties().forEach(copy::setProperty);
        copy.setException(exchange.getException());
        return copy;
    }

    void beforeInvocation(Map<String, Object> controls) {
        beforeInvocation();
        redeliveryCount = ((Number) controls.getOrDefault("expectedRedeliveryCount", 0)).intValue();
    }

    void beforeInvocation() {
        assertTrue(completed.isEmpty(), "JMS trigger delivered an unexpected extra message.");
        received.clear();
        invocationPublishedIds.clear();
        invocationCompletions.clear();
        sessions.clear();
        collectedCompletions = 0;
        redeliveryCount = 0;
        afterPublish = messages -> { };
    }

    void verifyInvocation(SnapshotFixtureInteraction interaction, String invocationId) throws Exception {
        String description = "JMS trigger invocation '" + invocationId + "'";
        SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
        List<String> expectedIds = invocationPublishedIds.stream().filter(id -> !expiredIds.contains(id)).toList();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (collectedCompletions < expectedIds.size()) {
            collectCompletion(expectedIds.get(collectedCompletions), invocationId, deadline);
        }
        assertTrue(completed.isEmpty(), description + " completed an unexpected extra message.");
        assertEquals(expected.getCount(), received.size(), description + " received an unexpected number of messages.");
        Map<String, Object> controls = interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
        if (controls.get("expectedCompletions") instanceof List<?> values) {
            List<SnapshotFixtureExchangeExpectation> expectations = completionExpectations(values);
            assertEquals(expectations.size(), invocationCompletions.size(), description + " completion count");
            for (int index = 0; index < expectations.size(); index++) {
                SnapshotFixtureExchangeExpectation expectation = expectations.get(index);
                Exchange completion = invocationCompletions.get(index);
                String completionDescription = description + " completion " + (index + 1);
                if (expectation.hasBody()) {
                    assertBody(expectation.getBody(), completion.getMessage().getBody(), completionDescription);
                }
                SnapshotValueAssertions.assertMapValues(expectation.getHeaders(), completion.getMessage().getHeaders(),
                        completionDescription + " header");
                SnapshotValueAssertions.assertMapValues(expectation.getProperties(), completion.getAllProperties(),
                        completionDescription + " property");
                SnapshotFailureAssertions.assertMatches(expectation.getExpectedFailure(), completion.getException(), completionDescription);
            }
        }
        for (Exchange completion : invocationCompletions) {
            if (controls.containsKey("expectedCompletionBody")) {
                assertBody(controls.get("expectedCompletionBody"), completion.getMessage().getBody(), description + " completion");
            }
            assertCompletionValues(controls, "expectedCompletionHeaders", completion.getMessage().getHeaders(), description);
            assertCompletionValues(controls, "expectedCompletionProperties", completion.getAllProperties(), description);
        }
        if (controls.containsKey("expectedAcknowledgmentMode") || controls.containsKey("expectedTransacted")) {
            assertEquals(received.size(), sessions.size(), description + " must observe each consumer session.");
            for (SessionObservation observed : sessions) {
                if (controls.containsKey("expectedAcknowledgmentMode")) {
                    assertEquals(((Number) controls.get("expectedAcknowledgmentMode")).intValue(), observed.acknowledgmentMode(),
                            description + " consumer session acknowledgment mode");
                }
                if (controls.containsKey("expectedTransacted")) {
                    assertEquals(controls.get("expectedTransacted"), observed.transacted(), description + " consumer session transaction mode");
                }
            }
        }
        if (interaction.hasExpectedExchanges()) {
            List<SnapshotFixtureExchangeExpectation> expectations = new ArrayList<>();
            for (SnapshotFixtureExchangeExpectation expectation : interaction.getExpectedExchanges()) {
                for (int index = 0; index < expectation.getCount(); index++) {
                    expectations.add(expectation);
                }
            }
            assertEquals(expectations.size(), received.size(), description + " ordered message count");
            for (int index = 0; index < expectations.size(); index++) {
                SnapshotFixtureExchangeExpectation expectation = expectations.get(index);
                Exchange input = received.get(index);
                String messageDescription = description + " message " + (index + 1);
                if (expectation.hasBody()) {
                    assertBody(expectation.getBody(), input.getMessage().getBody(), messageDescription);
                }
                SnapshotValueAssertions.assertMapValues(expectation.getHeaders(), input.getMessage().getHeaders(),
                        messageDescription + " header");
                SnapshotValueAssertions.assertMapValues(expectation.getProperties(), input.getAllProperties(),
                        messageDescription + " property");
            }
        }
        for (Exchange input : received) {
            Object body = input.getMessage().getBody();
            if (expected.hasBody()) {
                assertBody(expected.getBody(), body, description);
            }
            if (expected.getDestination() != null) {
                assertEquals(expected.getDestination(), destinationName, description + " destination");
            }
            SnapshotValueAssertions.assertMapValues(expected.getHeaders(), input.getMessage().getHeaders(), description + " header");
            SnapshotValueAssertions.assertMapValues(expected.getProperties(), input.getAllProperties(), description + " property");
            if (controls.containsKey("expectedBodyType")) {
                Class<?> type = Class.forName((String) controls.get("expectedBodyType"));
                assertTrue(type.isInstance(body), description + " body must be an instance of " + type.getName());
            }
            if (controls.containsKey("expectedBodyBytes")) {
                assertArrayEquals(bytes(controls.get("expectedBodyBytes")), bodyBytes(body), description + " bytes");
            }
            for (Object name : (List<?>) controls.getOrDefault("expectedAbsentHeaders", List.of())) {
                assertFalse(input.getMessage().getHeaders().containsKey(name), description + " must omit header '" + name + "'.");
            }
        }
        if (checkDeadLetters || controls.containsKey("expectedDeadLetterCount")) {
            checkDeadLetters = true;
            verifyDeadLetters(((Number) controls.getOrDefault("expectedDeadLetterCount", 0)).intValue(), description);
        }
    }

    private void verifyDeadLetters(int expectedCount, String description) throws Exception {
        List<String> ids = invocationPublishedIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            assertEquals(0, expectedCount, description + " cannot dead-letter a message that was not published.");
            return;
        }
        try (MessageConsumer deadLetters = deadLetterConsumer(ids)) {
            Set<String> observedIds = new LinkedHashSet<>();
            for (int index = 0; index < expectedCount; index++) {
                Message message = deadLetters.receive(10000);
                assertNotNull(message, description + " did not reach the dead-letter queue within 10000 ms.");
                String id = message.getJMSMessageID();
                assertTrue(ids.contains(id), description + " dead-lettered an unexpected message ID.");
                assertTrue(observedIds.add(id), description + " dead-lettered the same publication more than once.");
                Exchange original = received.stream().filter(exchange -> id.equals(exchange.getMessage().getHeader("JMSMessageID")))
                        .findFirst().orElseThrow(() -> new AssertionError(description + " dead-lettered a message without a recorded delivery."));
                Exchange observed = new DefaultExchange(original.getContext(), ExchangePattern.InOnly);
                observed.setIn(new JmsMessage(observed, message, session, new JmsBinding()));
                assertBody(original.getMessage().getBody(), observed.getMessage().getBody(), description + " dead-letter");
                for (var property : publishedProperties.get(id).entrySet()) {
                    assertEquals(property.getValue(), message.getObjectProperty((String) property.getKey()),
                            description + " dead-letter property " + property.getKey());
                }
            }
            assertNull(deadLetters.receive(250), description + " left an unexpected dead-letter message.");
        }
    }

    private MessageConsumer deadLetterConsumer(List<String> ids) throws Exception {
        String selector = "JMSMessageID IN (" + ids.stream().map(id -> "'" + id.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(",")) + ")";
        return session.createConsumer(session.createQueue("DLQ"), selector);
    }

    static List<SnapshotFixtureExchangeExpectation> completionExpectations(List<?> values) {
        List<SnapshotFixtureExchangeExpectation> expectations = new ArrayList<>();
        for (Object value : values) {
            SnapshotFixtureExchangeExpectation expectation = EXPECTATION_MAPPER.convertValue(value, SnapshotFixtureExchangeExpectation.class);
            for (int index = 0; index < expectation.getCount(); index++) {
                expectations.add(expectation);
            }
        }
        return expectations;
    }

    private Exchange collectCompletion(String messageId, String invocationId, long deadline) throws Exception {
        Exchange result = completed.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        assertNotNull(result, "JMS trigger invocation '" + invocationId + "' did not complete within 20 seconds.");
        assertEquals(messageId, result.getMessage().getHeader("JMSMessageID"),
                "JMS trigger completed an unexpected message for invocation '" + invocationId + "'.");
        collectedCompletions++;
        return result;
    }

    private static void assertCompletionValues(Map<String, Object> controls, String key, Map<String, Object> actual,
                                               String description) {
        if (controls.get(key) instanceof Map<?, ?> values) {
            Map<String, Object> expected = new LinkedHashMap<>();
            values.forEach((name, value) -> expected.put((String) name, value));
            SnapshotValueAssertions.assertMapValues(expected, actual, description + " completion " + key);
        }
    }

    private static void assertBody(Object expected, Object body, String description) throws Exception {
        Object actual = body instanceof byte[] || body instanceof ByteArrayInputStream
                ? new String(bodyBytes(body), StandardCharsets.UTF_8) : body;
        if (expected instanceof Map<?, ?> || expected instanceof List<?>) {
            SnapshotValueAssertions.assertMatches(MAPPER.valueToTree(expected),
                    actual instanceof String text ? MAPPER.readTree(text) : MAPPER.valueToTree(actual), description + " body");
        } else {
            assertEquals(expected, actual, description + " body");
        }
    }

    private static byte[] bodyBytes(Object body) {
        if (body instanceof ByteArrayInputStream input) {
            byte[] bytes = input.readAllBytes();
            input.reset();
            return bytes;
        }
        return (byte[]) body;
    }

    void verifyAfterConsumerStopped() throws Exception {
        assertTrue(receivedIds.stream().noneMatch(expiredIds::contains), "JMS trigger must not receive expired messages.");
        assertTrue(completedIds.stream().noneMatch(expiredIds::contains), "JMS trigger must not complete expired messages.");
        List<String> expectedIds = publishedIds.stream().filter(id -> !expiredIds.contains(id)).toList();
        assertEquals(expectedIds, receivedIds, "JMS trigger must receive each published message once and in order.");
        assertEquals(expectedIds, completedIds, "JMS trigger must complete each published message once and in order.");
        assertTrue(completed.isEmpty(), "JMS trigger completed an unexpected extra message.");
        if (checkDeadLetters && !publishedIds.isEmpty()) {
            try (MessageConsumer deadLetters = deadLetterConsumer(publishedIds.stream().distinct().toList())) {
                assertNull(deadLetters.receive(250), "JMS trigger left an unexpected dead-letter message after consumer shutdown.");
            }
        }
        List<Destination> observedDestinations = new ArrayList<>();
        if (!topic) {
            observedDestinations.add(destination);
        }
        for (String queue : replyQueues) {
            observedDestinations.add(session.createQueue(queue));
        }
        for (Destination observed : observedDestinations) {
            try (MessageConsumer consumer = session.createConsumer(observed)) {
                assertNull(consumer.receive(250), "JMS trigger left an unconsumed message or sent an unexpected reply to " + observed);
            }
        }
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
