package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.apache.camel.component.springrabbit.SpringRabbitMQEndpoint;
import org.apache.camel.support.DefaultExchange;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotFailureAssertions;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class RabbitMqTriggerSnapshotRuntime implements AutoCloseable {
    static final String PUBLICATION_ID = "snapshotPublicationId";
    private static final String CONSUMER_ID = "snapshotRabbitConsumerId";
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private static final long WAIT_SECONDS = 20;
    private final ConnectionFactory publisherFactory;
    private final String exchange;
    private final String routingKey;
    private final List<String> queues;
    private final BlockingQueue<Exchange> completed = new LinkedBlockingQueue<>();
    private final List<Exchange> received = new CopyOnWriteArrayList<>();
    private final List<Exchange> business = new CopyOnWriteArrayList<>();
    private final List<Exchange> invocationCompletions = new CopyOnWriteArrayList<>();
    private final List<PublishedMessage> published = new CopyOnWriteArrayList<>();
    private final Map<String, AMQP.BasicProperties> publicationProperties = new ConcurrentHashMap<>();
    private final Set<String> consumerIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean deliveryHeldOnce = new AtomicBoolean();
    private final AtomicBoolean completionHeldOnce = new AtomicBoolean();
    private CamelContext context;
    private Connection publisherConnection;
    private Channel publisherChannel;
    private AfterPublish afterPublish = records -> { };
    private Map<String, Object> controls = Map.of();
    private volatile CountDownLatch deliveryHeld;
    private volatile CountDownLatch releaseDelivery;
    private volatile CountDownLatch completionHeld;
    private volatile CountDownLatch releaseCompletion;
    private volatile CountDownLatch concurrentConsumers;
    private volatile CountDownLatch releaseConcurrentConsumers;

    @FunctionalInterface
    interface AfterPublish {
        void accept(List<PublishedMessage> records) throws Exception;
    }

    public record PublishedMessage(String id, String exchange, String routingKey, byte[] body, AMQP.BasicProperties properties) {
    }

    RabbitMqTriggerSnapshotRuntime(ConnectionFactory publisherFactory, String exchange, String routingKey, List<String> queues) {
        this.publisherFactory = publisherFactory;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.queues = List.copyOf(queues);
    }

    public static String registryName(String nodeId) {
        return "snapshot-rabbitmq-trigger:" + nodeId;
    }

    void context(CamelContext context) {
        this.context = context;
    }

    public Exchange send(SnapshotScenarioInvocation invocation, Map<String, Object> parameters) throws Exception {
        List<PublishedMessage> records = new ArrayList<>();
        if (parameters.get("messages") instanceof List<?> messages) {
            for (Object value : messages) {
                Map<?, ?> message = (Map<?, ?>) value;
                Map<String, Object> options = new LinkedHashMap<>(parameters);
                options.remove("messages");
                message.forEach((key, item) -> options.put(String.valueOf(key), item));
                Map<?, ?> headers = message.get("headers") instanceof Map<?, ?> values ? values : Map.of();
                records.add(publish(message.get("body"), headers, options));
            }
        } else {
            records.add(publish(invocation.getBody(), invocation.getHeaders(), parameters));
        }
        published.addAll(records);
        afterPublish.accept(List.copyOf(records));
        int expected = parameters.get("expectedCompletions") instanceof Number count ? count.intValue()
                : records.size() + number(controls, "expectedRedeliveryCount", 0);
        Set<String> ids = new LinkedHashSet<>(records.stream().map(PublishedMessage::id).toList());
        List<Exchange> results = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        for (int index = 0; index < expected; index++) {
            Exchange result = completed.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(result, "RabbitMQ invocation '" + invocation.getId() + "' did not complete within 20 seconds.");
            assertTrue(ids.contains(publicationId(result)), "RabbitMQ trigger completed an unexpected publication.");
            results.add(result);
        }
        int redeliveries = number(controls, "expectedRedeliveryCount", 0);
        if (expected > 0 && expected == records.size() + redeliveries) {
            Map<String, Long> completionCounts = new LinkedHashMap<>();
            results.forEach(result -> completionCounts.merge(publicationId(result), 1L, Long::sum));
            assertEquals(ids, completionCounts.keySet(), "RabbitMQ must complete every published message.");
            if (redeliveries == 0 || records.size() == 1) {
                Map<String, Long> expectedCounts = new LinkedHashMap<>();
                ids.forEach(id -> expectedCounts.put(id, 1L + redeliveries));
                assertEquals(expectedCounts, completionCounts, "RabbitMQ publication completion counts");
            }
        }
        if (results.isEmpty()) {
            Exchange result = new DefaultExchange(context, ExchangePattern.InOnly);
            result.getMessage().setBody(null);
            result.getMessage().setHeader("snapshotPublishedCount", records.size());
            return result;
        }
        Map<String, Exchange> byPublication = new LinkedHashMap<>();
        results.forEach(result -> byPublication.put(publicationId(result), result));
        List<Exchange> ordered = records.stream().map(record -> byPublication.get(record.id())).filter(java.util.Objects::nonNull).toList();
        Exchange result = copy(ordered.getLast(), null);
        if (parameters.containsKey("messages")) {
            result.getMessage().setBody(ordered.stream().map(item -> item.getMessage().getBody()).toList());
        }
        ordered.stream().filter(item -> item.getException() != null).findFirst()
                .ifPresent(failed -> result.setException(failed.getException()));
        return result;
    }

    private PublishedMessage publish(Object body, Map<?, ?> inputHeaders, Map<String, Object> options) throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> headers = new LinkedHashMap<>();
        inputHeaders.forEach((key, value) -> headers.put(String.valueOf(key), value));
        if (options.get("headersBase64") instanceof Map<?, ?> binaryHeaders) {
            binaryHeaders.forEach((key, value) -> headers.put(String.valueOf(key), Base64.getDecoder().decode((String) value)));
        }
        headers.put(PUBLICATION_ID, id);
        String targetExchange = (String) options.getOrDefault("exchange", exchange);
        String targetKey = (String) options.getOrDefault("routingKey", routingKey);
        byte[] bytes = RabbitMqTriggerSnapshotRecordCodec.encode(body, options);
        AMQP.BasicProperties properties = RabbitMqTriggerSnapshotRecordCodec.properties(options, headers);
        openPublisher();
        publicationProperties.put(id, properties);
        publisherChannel.basicPublish(targetExchange, targetKey, false, properties, bytes);
        publisherChannel.waitForConfirmsOrDie(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        return new PublishedMessage(id, targetExchange, targetKey, bytes, properties);
    }

    private void openPublisher() throws Exception {
        if (publisherConnection == null || !publisherConnection.isOpen()) {
            resetPublisher();
            publisherConnection = publisherFactory.newConnection("snapshot-rabbitmq-trigger-publisher");
            publisherChannel = publisherConnection.createChannel();
            publisherChannel.confirmSelect();
        }
    }

    void beforeInvocation(Map<String, Object> controls) {
        assertTrue(completed.isEmpty(), "RabbitMQ trigger completed an unexpected extra publication.");
        this.controls = controls;
        received.clear();
        business.clear();
        invocationCompletions.clear();
        published.clear();
        publicationProperties.clear();
        consumerIds.clear();
        afterPublish = records -> { };
        deliveryHeldOnce.set(false);
        completionHeldOnce.set(false);
        deliveryHeld = null;
        releaseDelivery = null;
        completionHeld = null;
        releaseCompletion = null;
        concurrentConsumers = null;
        releaseConcurrentConsumers = null;
    }

    void afterPublish(AfterPublish callback) {
        afterPublish = callback;
    }

    void received(Exchange exchange) throws Exception {
        received(exchange, exchange.getFromRouteId());
    }

    void received(Exchange exchange, String consumerId) throws Exception {
        AMQP.BasicProperties publication = publicationProperties.get(publicationId(exchange));
        assertNotNull(publication, "RabbitMQ trigger received a publication that was not sent by its native publisher.");
        SpringRabbitMQEndpoint endpoint = assertInstanceOf(SpringRabbitMQEndpoint.class, exchange.getFromEndpoint());
        ExchangePattern expectedPattern = publication.getReplyTo() != null && !endpoint.isDisableReplyTo()
                ? ExchangePattern.InOut : ExchangePattern.InOnly;
        assertEquals(expectedPattern, exchange.getPattern(), "RabbitMQ trigger exchange pattern must follow native reply-to semantics.");
        assertEquals(publication.getReplyTo(), exchange.getMessage().getHeader(SpringRabbitMQConstants.REPLY_TO),
                "RabbitMQ trigger must preserve the native publication's reply-to property.");
        received.add(copy(exchange, consumerId));
        if (consumerId != null && consumerIds.add(consumerId) && concurrentConsumers != null) {
            concurrentConsumers.countDown();
            assertTrue(releaseConcurrentConsumers.await(30, TimeUnit.SECONDS), "RabbitMQ concurrent deliveries were not released.");
        }
    }

    List<Exchange> received() {
        return List.copyOf(received);
    }

    void business(Exchange exchange) throws Exception {
        business(exchange, exchange.getFromRouteId());
    }

    void business(Exchange exchange, String consumerId) throws Exception {
        business.add(copy(exchange, consumerId));
        if (deliveryHeld != null && deliveryHeldOnce.compareAndSet(false, true)) {
            deliveryHeld.countDown();
            assertTrue(releaseDelivery.await(30, TimeUnit.SECONDS), "RabbitMQ fixture did not release the held delivery.");
        }
    }

    void completed(Exchange exchange) throws InterruptedException {
        completed(exchange, exchange.getFromRouteId());
    }

    void completed(Exchange exchange, String consumerId) throws InterruptedException {
        Exchange snapshot = copy(exchange, consumerId);
        if (snapshot.getException() == null && !exchange.getExchangeExtension().isFailureHandled()) {
            snapshot.setException(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class));
        }
        if (completionHeld != null && completionHeldOnce.compareAndSet(false, true)) {
            completionHeld.countDown();
            assertTrue(releaseCompletion.await(30, TimeUnit.SECONDS), "RabbitMQ fixture did not release route completion.");
        }
        invocationCompletions.add(snapshot);
        completed.add(snapshot);
    }

    void verify(SnapshotFixtureInteraction interaction, String invocationId) throws Exception {
        verify(interaction, invocationId, null);
    }

    void verify(SnapshotFixtureInteraction interaction, String invocationId, String consumerId) throws Exception {
        String description = "RabbitMQ trigger invocation '" + invocationId + "'";
        SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
        Map<String, Object> properties = interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
        List<Exchange> inputs = select(received, consumerId);
        List<Exchange> executions = select(business, consumerId);
        List<Exchange> completions = select(invocationCompletions, consumerId);
        assertEquals(expected.getCount(), inputs.size(), description + " native delivery count");
        assertCount(properties, "expectedBusinessCount", executions.size(), description);
        assertCount(properties, "expectedTotalBusinessCount", business.size(), description);
        assertCount(properties, "expectedConsumerCount", consumerIds.size(), description);
        assertCount(properties, "expectedDuplicateCompletions", duplicates(completions), description);
        assertCount(properties, "expectedTotalDuplicateCompletions", duplicates(invocationCompletions), description);
        assertCount(properties, "expectedFailureCompletions", failures(completions), description);
        assertCount(properties, "expectedTotalFailureCompletions", failures(invocationCompletions), description);
        if (properties.containsKey("expectedRedeliveryCount")) {
            long redeliveries = inputs.stream().filter(input -> Boolean.TRUE.equals(
                    input.getMessage().getHeader(SpringRabbitMQConstants.REDELIVERED))).count();
            assertEquals(((Number) properties.get("expectedRedeliveryCount")).longValue(), redeliveries,
                    description + " native redelivery count");
        }
        List<Exchange> observations = new ArrayList<>();
        for (Exchange input : inputs) {
            Exchange observation = executions.stream().filter(item -> publicationId(item).equals(publicationId(input))
                    && java.util.Objects.equals(item.getMessage().getHeader(SpringRabbitMQConstants.DELIVERY_TAG),
                    input.getMessage().getHeader(SpringRabbitMQConstants.DELIVERY_TAG))
                    && java.util.Objects.equals(item.getMessage().getHeader(SpringRabbitMQConstants.REDELIVERED),
                    input.getMessage().getHeader(SpringRabbitMQConstants.REDELIVERED))).findFirst().orElse(input);
            observations.add(observation);
            assertInput(expected, properties, observation, description);
        }
        if (interaction.hasExpectedExchanges()) {
            List<SnapshotFixtureExchangeExpectation> expectations = expand(interaction.getExpectedExchanges());
            assertEquals(expectations.size(), observations.size(), description + " ordered input count");
            for (int index = 0; index < expectations.size(); index++) {
                assertExchange(expectations.get(index), observations.get(index), description + " input " + (index + 1), false);
            }
        }
        if (properties.get("expectedCompletions") instanceof List<?> values) {
            List<SnapshotFixtureExchangeExpectation> expectations = completionExpectations(values);
            assertEquals(expectations.size(), completions.size(), description + " completion count");
            for (int index = 0; index < expectations.size(); index++) {
                assertExchange(expectations.get(index), completions.get(index), description + " completion " + (index + 1), true);
            }
        }
        for (Exchange completion : completions) {
            if (properties.containsKey("expectedCompletionBody")) {
                assertBody(properties.get("expectedCompletionBody"), completion.getMessage().getBody(), description + " completed body");
            }
            assertMap(properties, "expectedCompletionHeaders", completion.getMessage().getHeaders(), description);
            assertMap(properties, "expectedCompletionProperties", completion.getAllProperties(), description);
        }
        assertTrue(completed.isEmpty(), description + " completed an unexpected extra publication.");
    }

    private void assertInput(SnapshotFixtureRequestExpectation expected, Map<String, Object> properties,
                             Exchange input, String description) throws Exception {
        if (expected.hasBody()) {
            assertBody(expected.getBody(), input.getMessage().getBody(), description + " input body");
        }
        Map<String, Object> headers = RabbitMqTriggerSnapshotRecordCodec.normalizeHeaders(input.getMessage().getHeaders());
        SnapshotValueAssertions.assertMapValues(expected.getHeaders(), headers, description + " input header");
        SnapshotValueAssertions.assertMapValues(expected.getProperties(), input.getAllProperties(), description + " input property");
        if (expected.getDestination() != null) {
            assertEquals(expected.getDestination(), input.getMessage().getHeader(SpringRabbitMQConstants.EXCHANGE_NAME),
                    description + " input exchange");
        }
        if (expected.getKey() != null) {
            assertEquals(expected.getKey(), input.getMessage().getHeader(SpringRabbitMQConstants.ROUTING_KEY), description + " input routing key");
        }
        byte[] bytes = RabbitMqTriggerSnapshotRecordCodec.bodyBytes(input.getMessage().getBody());
        if (properties.containsKey("expectedBodyBase64")) {
            assertArrayEquals(Base64.getDecoder().decode((String) properties.get("expectedBodyBase64")), bytes, description + " input bytes");
        }
        if (properties.containsKey("expectedBodyLength")) {
            assertEquals(((Number) properties.get("expectedBodyLength")).intValue(), bytes.length, description + " input byte length");
        }
        if (properties.containsKey("expectedBodySha256")) {
            assertEquals(properties.get("expectedBodySha256"), RabbitMqTriggerSnapshotRecordCodec.sha256(bytes), description + " input digest");
        }
        if (properties.containsKey("expectedBodyType")) {
            assertTrue(Class.forName((String) properties.get("expectedBodyType")).isInstance(input.getMessage().getBody()),
                    description + " input body type");
        }
        for (Object name : (List<?>) properties.getOrDefault("expectedAbsentHeaders", List.of())) {
            assertFalse(headers.containsKey(name), description + " must omit header '" + name + "'.");
        }
    }

    static List<SnapshotFixtureExchangeExpectation> completionExpectations(List<?> values) {
        List<SnapshotFixtureExchangeExpectation> result = new ArrayList<>();
        for (Object value : values) {
            SnapshotFixtureExchangeExpectation expectation = MAPPER.convertValue(value, SnapshotFixtureExchangeExpectation.class);
            for (int index = 0; index < expectation.getCount(); index++) {
                result.add(expectation);
            }
        }
        return result;
    }

    private static List<SnapshotFixtureExchangeExpectation> expand(List<SnapshotFixtureExchangeExpectation> values) {
        List<SnapshotFixtureExchangeExpectation> result = new ArrayList<>();
        for (SnapshotFixtureExchangeExpectation value : values) {
            for (int index = 0; index < value.getCount(); index++) {
                result.add(value);
            }
        }
        return result;
    }

    private static void assertExchange(SnapshotFixtureExchangeExpectation expected, Exchange actual,
                                       String description, boolean failure) {
        if (expected.hasBody()) {
            assertBody(expected.getBody(), actual.getMessage().getBody(), description + " body");
        }
        SnapshotValueAssertions.assertMapValues(expected.getHeaders(),
                RabbitMqTriggerSnapshotRecordCodec.normalizeHeaders(actual.getMessage().getHeaders()), description + " header");
        SnapshotValueAssertions.assertMapValues(expected.getProperties(), actual.getAllProperties(), description + " property");
        if (failure) {
            SnapshotFailureAssertions.assertMatches(expected.getExpectedFailure(), actual.getException(), description);
        }
    }

    private static void assertBody(Object expected, Object actual, String description) {
        if (actual instanceof byte[] bytes && expected instanceof String text) {
            assertEquals(text, new String(bytes, StandardCharsets.UTF_8), description);
        } else if (actual instanceof byte[] actualBytes && expected instanceof byte[] expectedBytes) {
            assertArrayEquals(expectedBytes, actualBytes, description);
        } else {
            assertEquals(expected, actual, description);
        }
    }

    private static void assertMap(Map<String, Object> properties, String key, Map<String, Object> actual, String description) {
        if (properties.get(key) instanceof Map<?, ?> values) {
            Map<String, Object> expected = new LinkedHashMap<>();
            values.forEach((name, value) -> expected.put(String.valueOf(name), value));
            SnapshotValueAssertions.assertMapValues(expected, actual, description + " " + key);
        }
    }

    private static void assertCount(Map<String, Object> properties, String key, long actual, String description) {
        if (properties.get(key) instanceof Number expected) {
            assertEquals(expected.longValue(), actual, description + " " + key);
        }
    }

    private static long duplicates(List<Exchange> exchanges) {
        return exchanges.stream().filter(exchange -> Boolean.TRUE.equals(exchange.getProperty("CamelDuplicateMessage"))).count();
    }

    private static long failures(List<Exchange> exchanges) {
        return exchanges.stream().filter(exchange -> exchange.getException() != null).count();
    }

    private static List<Exchange> select(List<Exchange> exchanges, String consumerId) {
        return consumerId == null ? List.copyOf(exchanges) : exchanges.stream()
                .filter(exchange -> consumerId.equals(exchange.getProperty(CONSUMER_ID))).toList();
    }

    private static Exchange copy(Exchange exchange, String consumerId) {
        Exchange copy = new DefaultExchange(exchange.getContext(), exchange.getPattern());
        Object body = exchange.getMessage().getBody();
        copy.getMessage().setBody(body instanceof byte[] bytes ? bytes.clone() : body);
        copy.getMessage().setHeaders(new LinkedHashMap<>(exchange.getMessage().getHeaders()));
        exchange.getAllProperties().forEach(copy::setProperty);
        if (consumerId != null) {
            copy.setProperty(CONSUMER_ID, consumerId);
        }
        copy.setException(exchange.getException());
        return copy;
    }

    private static int number(Map<String, Object> properties, String key, int fallback) {
        return properties.get(key) instanceof Number value ? value.intValue() : fallback;
    }

    static String publicationId(Exchange exchange) {
        return exchange.getMessage().getHeader(PUBLICATION_ID, String.class);
    }

    void holdFirstDelivery() {
        deliveryHeld = new CountDownLatch(1);
        releaseDelivery = new CountDownLatch(1);
    }

    void awaitHeldDelivery() throws InterruptedException {
        await(deliveryHeld, "RabbitMQ consumer did not reach the held delivery.");
    }

    void releaseHeldDelivery() {
        release(releaseDelivery);
    }

    void holdCompletion() {
        completionHeld = new CountDownLatch(1);
        releaseCompletion = new CountDownLatch(1);
    }

    void awaitHeldCompletion() throws InterruptedException {
        await(completionHeld, "RabbitMQ consumer did not reach route completion.");
    }

    void releaseCompletion() {
        release(releaseCompletion);
    }

    void holdConcurrentConsumers(int count) {
        concurrentConsumers = new CountDownLatch(count);
        releaseConcurrentConsumers = new CountDownLatch(1);
    }

    void awaitConcurrentConsumers() throws InterruptedException {
        await(concurrentConsumers, "RabbitMQ consumers did not receive concurrently.");
    }

    void releaseConcurrentConsumers() {
        release(releaseConcurrentConsumers);
    }

    private static void await(CountDownLatch latch, String description) throws InterruptedException {
        assertNotNull(latch, "RabbitMQ observation gate is not configured.");
        assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), description);
    }

    private static void release(CountDownLatch latch) {
        if (latch != null) {
            latch.countDown();
        }
    }

    void assertNothingDelivered() {
        assertTrue(received.isEmpty(), "RabbitMQ trigger received a publication while its consumer was unavailable.");
        assertTrue(completed.isEmpty(), "RabbitMQ trigger completed a publication while its consumer was unavailable.");
    }

    List<PublishedMessage> published() {
        return List.copyOf(published);
    }

    List<Exchange> businessExchanges() {
        return List.copyOf(business);
    }

    List<Exchange> completions() {
        return List.copyOf(invocationCompletions);
    }

    List<String> queues() {
        return queues;
    }

    void resetPublisher() throws Exception {
        try {
            if (publisherChannel != null && publisherChannel.isOpen()) {
                publisherChannel.close();
            }
        } finally {
            publisherChannel = null;
            if (publisherConnection != null) {
                try {
                    if (publisherConnection.isOpen()) {
                        publisherConnection.close();
                    }
                } finally {
                    publisherConnection = null;
                }
            }
        }
    }

    @Override
    public void close() throws Exception {
        releaseHeldDelivery();
        releaseCompletion();
        releaseConcurrentConsumers();
        resetPublisher();
    }
}
