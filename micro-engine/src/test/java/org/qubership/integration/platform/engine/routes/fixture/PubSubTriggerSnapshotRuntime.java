package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.google.pubsub.GooglePubsubConstants;
import org.apache.camel.component.google.pubsub.consumer.GooglePubsubAcknowledge;
import org.apache.camel.support.DefaultExchange;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotFailureAssertions;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PubSubTriggerSnapshotRuntime {
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper().copy()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private final PubSubTriggerSnapshotBroker broker;
    private final CamelContext context;
    private final boolean manualAcknowledgment;
    private final BlockingQueue<Exchange> completions = new LinkedBlockingQueue<>();
    private final List<Exchange> inputs = new CopyOnWriteArrayList<>();
    private final List<Exchange> finished = new CopyOnWriteArrayList<>();
    private final Map<String, PubsubMessage> published = new LinkedHashMap<>();
    private final List<String> currentIds = new ArrayList<>();
    private final List<String> allCompletedIds = new CopyOnWriteArrayList<>();
    private SnapshotFixtureInteraction interaction;
    private Runnable afterPublish = () -> { };
    private volatile List<String> currentPublishedMessageIds = List.of();
    private volatile String phase = "not-started";

    PubSubTriggerSnapshotRuntime(PubSubTriggerSnapshotBroker broker, CamelContext context, boolean manualAcknowledgment) {
        this.broker = broker;
        this.context = context;
        this.manualAcknowledgment = manualAcknowledgment;
    }

    public static String registryName(String nodeId) {
        return "snapshot-pubsub-trigger:" + nodeId;
    }

    public Exchange send(SnapshotScenarioInvocation invocation, Map<String, Object> parameters) throws Exception {
        if (Boolean.TRUE.equals(parameters.get("observeOnly"))) {
            phase = "observing";
            Exchange result = new DefaultExchange(context, ExchangePattern.InOnly);
            result.getMessage().setBody("Observed");
            return result;
        }
        List<PubsubMessage> messages = messages(invocation.getBody(), invocation.getHeaders(), parameters);
        phase = "publishing";
        List<String> ids = broker.publish(messages).getMessageIdsList();
        currentPublishedMessageIds = List.copyOf(ids);
        assertEquals(messages.size(), ids.size(), "Pub/Sub must assign one ID per published message.");
        for (int index = 0; index < ids.size(); index++) {
            assertFalse(published.containsKey(ids.get(index)), "Pub/Sub must assign distinct message IDs.");
            published.put(ids.get(index), messages.get(index));
        }
        currentIds.addAll(ids);
        phase = "after-publish";
        afterPublish.run();
        int expectedCount = interaction.getExpectedRequest().getCount();
        Map<String, Exchange> lastById = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        phase = "waiting-for-completions";
        for (int index = 0; index < expectedCount; index++) {
            Exchange completion = completions.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(completion, "Pub/Sub invocation '" + invocation.getId() + "' did not complete within 30 seconds.");
            String id = messageId(completion);
            assertTrue(ids.contains(id), "Pub/Sub completed an unexpected message: " + id);
            lastById.put(id, completion);
        }
        assertEquals(ids.size(), lastById.size(), "Every published message must complete.");
        List<Exchange> ordered = ids.stream().map(lastById::get).toList();
        Exchange result = copy(ordered.getLast());
        if (parameters.containsKey("messages") || parameters.containsKey("messageCount")) {
            result.getMessage().setBody(ordered.stream().map(exchange -> exchange.getMessage().getBody()).toList());
        }
        phase = "completed";
        return result;
    }

    public static List<PubsubMessage> messages(Object body, Map<String, Object> headers, Map<String, Object> parameters) {
        List<PubsubMessage> result = new ArrayList<>();
        if (parameters.get("messages") instanceof List<?> messages) {
            for (Object value : messages) {
                Map<?, ?> message = (Map<?, ?>) value;
                Map<String, Object> options = new LinkedHashMap<>(parameters);
                options.remove("messages");
                message.forEach((key, item) -> options.put((String) key, item));
                appendMessages(result, message.get("body"), headers, options);
            }
        } else {
            appendMessages(result, body, headers, parameters);
        }
        return result;
    }

    private static void appendMessages(List<PubsubMessage> result, Object body, Map<String, Object> headers,
                                       Map<String, Object> options) {
        Map<String, String> attributes = attributes(headers);
        if (options.get("attributes") instanceof Map<?, ?> values) {
            attributes.putAll(attributes(values));
        }
        PubsubMessage.Builder message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFrom(bytes(body, options.get("dataEncoding"))))
                .putAllAttributes(attributes);
        if (options.get("orderingKey") instanceof String key) {
            message.setOrderingKey(key);
        }
        int count = ((Number) options.getOrDefault("messageCount", 1)).intValue();
        for (int index = 0; index < count; index++) {
            result.add(message.build());
        }
    }

    static byte[] bytes(Object body, Object encoding) {
        if ("bytes".equals(encoding)) {
            if (!(body instanceof List<?> values)) {
                throw new IllegalArgumentException("Pub/Sub bytes encoding requires a list of integers from 0 to 255.");
            }
            byte[] result = new byte[values.size()];
            for (int index = 0; index < values.size(); index++) {
                Object item = values.get(index);
                if (!(item instanceof Number value) || value.intValue() < 0 || value.intValue() > 255
                        || value.doubleValue() != value.intValue()) {
                    throw new IllegalArgumentException("Pub/Sub bytes encoding requires integers from 0 to 255.");
                }
                result[index] = (byte) value.intValue();
            }
            return result;
        }
        if (encoding != null && !"utf8".equals(encoding)) {
            throw new IllegalArgumentException("Pub/Sub dataEncoding supports utf8 and bytes.");
        }
        if (body == null) {
            return new byte[0];
        }
        if (!(body instanceof String text)) {
            throw new IllegalArgumentException("Pub/Sub UTF-8 payloads must be strings.");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, String> attributes(Map<?, ?> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String name) || !(value instanceof String text)) {
                throw new IllegalArgumentException("Pub/Sub attribute names and values must be strings.");
            }
            result.put(name, text);
        });
        return result;
    }

    void beforeInvocation(SnapshotFixtureInteraction expected) {
        assertTrue(completions.isEmpty(), "Pub/Sub delivered an unexpected completion between invocations.");
        inputs.clear();
        finished.clear();
        currentIds.clear();
        currentPublishedMessageIds = List.of();
        phase = "setup";
        interaction = expected;
        afterPublish = () -> { };
    }

    void afterPublish(Runnable callback) {
        afterPublish = callback;
    }

    String timeoutDiagnostics() {
        return "phase=" + phase
                + ", currentPublishedMessageIds=" + currentPublishedMessageIds
                + ", receivedMessageIds=" + inputs.stream().map(PubSubTriggerSnapshotRuntime::messageId).toList()
                + ", completedMessageIds=" + finished.stream().map(PubSubTriggerSnapshotRuntime::messageId).toList()
                + ", allCompletedMessageIds=" + List.copyOf(allCompletedIds)
                + ", pendingCompletions=" + completions.size();
    }

    void received(Exchange exchange) {
        assertEquals(ExchangePattern.InOnly, exchange.getPattern(), "Pub/Sub trigger must consume messages as InOnly.");
        inputs.add(copy(exchange));
    }

    void completed(Exchange exchange) {
        Exchange snapshot = copy(exchange);
        if (snapshot.getException() == null && !exchange.getExchangeExtension().isFailureHandled()) {
            snapshot.setException(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class));
        }
        finished.add(snapshot);
        allCompletedIds.add(messageId(snapshot));
        completions.add(snapshot);
    }

    void assertNothingDelivered() {
        assertTrue(inputs.isEmpty(), "Pub/Sub delivered a message while the consumer was unavailable.");
        assertTrue(completions.isEmpty(), "Pub/Sub completed a message while the consumer was unavailable.");
    }

    List<String> currentMessageIds() {
        return List.copyOf(currentIds);
    }

    void verifyInvocation(String invocationId) {
        String description = "Pub/Sub invocation '" + invocationId + "'";
        int count = interaction.getExpectedRequest().getCount();
        assertEquals(count, inputs.size(), description + " input count");
        assertEquals(count, finished.size(), description + " completion count");
        assertTrue(completions.isEmpty(), description + " has extra completions");
        Map<String, Object> controls = interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
        for (Exchange input : inputs) {
            if (manualAcknowledgment) {
                assertInstanceOf(GooglePubsubAcknowledge.class,
                        input.getMessage().getHeader(GooglePubsubConstants.GOOGLE_PUBSUB_ACKNOWLEDGE),
                        description + " manual acknowledgment");
            } else {
                assertFalse(input.getMessage().getHeaders().containsKey(GooglePubsubConstants.GOOGLE_PUBSUB_ACKNOWLEDGE),
                        description + " automatic acknowledgment must not expose a manual callback");
            }
            assertFalse(input.getMessage().getHeaders().containsKey(GooglePubsubConstants.ACK_ID),
                    description + " streaming pull must not expose a synchronous ack ID");
            String id = messageId(input);
            PubsubMessage message = published.get(id);
            assertNotNull(message, description + " received an unpublished message");
            assertArrayEquals(message.getData().toByteArray(), assertInstanceOf(byte[].class, input.getMessage().getBody()),
                    description + " published bytes");
            assertEquals(message.getAttributesMap(), input.getMessage().getHeader(GooglePubsubConstants.ATTRIBUTES),
                    description + " published attributes");
            Timestamp timestamp = assertInstanceOf(Timestamp.class,
                    input.getMessage().getHeader(GooglePubsubConstants.PUBLISH_TIME));
            assertEquals(broker.publishTime(id), timestamp, description + " broker publish time");
            assertTrue(timestamp.getSeconds() > 0, description + " publish time must be assigned by the broker");
            SnapshotValueAssertions.assertMapValues(interaction.getExpectedRequest().getHeaders(),
                    input.getMessage().getHeaders(), description + " input header");
            SnapshotValueAssertions.assertMapValues(interaction.getExpectedRequest().getProperties(),
                    input.getAllProperties(), description + " input property");
            for (Object name : (List<?>) controls.getOrDefault("expectedAbsentHeaders", List.of())) {
                assertFalse(input.getMessage().getHeaders().containsKey(name), description + " must omit header " + name);
            }
        }
        if (controls.get("expectedCompletions") instanceof List<?> values) {
            List<SnapshotFixtureExchangeExpectation> expectations = values.stream()
                    .map(value -> MAPPER.convertValue(value, SnapshotFixtureExchangeExpectation.class)).toList();
            List<SnapshotFixtureExchangeExpectation> expanded = new ArrayList<>();
            expectations.forEach(expected -> {
                for (int index = 0; index < expected.getCount(); index++) {
                    expanded.add(expected);
                }
            });
            assertEquals(expanded.size(), finished.size(), description + " completion expectations");
            for (int index = 0; index < finished.size(); index++) {
                SnapshotFixtureExchangeExpectation expected = expanded.get(index);
                Exchange actual = finished.get(index);
                if (expected.hasBody()) {
                    assertBody(expected.getBody(), actual.getMessage().getBody(), description + " completion body");
                }
                SnapshotValueAssertions.assertMapValues(expected.getHeaders(), actual.getMessage().getHeaders(), description + " completion header");
                SnapshotValueAssertions.assertMapValues(expected.getProperties(), actual.getAllProperties(), description + " completion property");
                SnapshotFailureAssertions.assertMatches(expected.getExpectedFailure(), actual.getException(), description + " completion failure");
            }
        } else {
            finished.forEach(exchange -> SnapshotFailureAssertions.assertMatches(null, exchange.getException(), description + " completion"));
        }
    }

    private static void assertBody(Object expected, Object actual, String description) {
        if (actual instanceof byte[] bytes && (expected instanceof List<?> || expected instanceof String)) {
            assertArrayEquals(bytes(expected, expected instanceof List<?> ? "bytes" : "utf8"), bytes, description);
        } else {
            assertEquals(expected, actual, description);
        }
    }

    void verifyStopped() {
        assertTrue(completions.isEmpty(), "Pub/Sub delivered an extra completion before shutdown.");
        assertTrue(allCompletedIds.stream().allMatch(published::containsKey), "Pub/Sub completed an unpublished message.");
    }

    void acknowledgePending() {
        for (Exchange input : inputs) {
            if (!broker.statistics().acknowledgedMessageIds().contains(messageId(input))) {
                GooglePubsubAcknowledge acknowledge = input.getMessage().getHeader(
                        GooglePubsubConstants.GOOGLE_PUBSUB_ACKNOWLEDGE, GooglePubsubAcknowledge.class);
                if (acknowledge != null) {
                    acknowledge.ack(input);
                }
            }
        }
    }

    private static String messageId(Exchange exchange) {
        return exchange.getMessage().getHeader(GooglePubsubConstants.MESSAGE_ID, String.class);
    }

    private static Exchange copy(Exchange exchange) {
        Exchange snapshot = new DefaultExchange(exchange.getContext(), exchange.getPattern());
        Object body = exchange.getMessage().getBody();
        snapshot.getMessage().setBody(body instanceof byte[] bytes ? bytes.clone() : body);
        snapshot.getMessage().setHeaders(new LinkedHashMap<>(exchange.getMessage().getHeaders()));
        exchange.getAllProperties().forEach(snapshot::setProperty);
        snapshot.setException(exchange.getException());
        return snapshot;
    }
}
