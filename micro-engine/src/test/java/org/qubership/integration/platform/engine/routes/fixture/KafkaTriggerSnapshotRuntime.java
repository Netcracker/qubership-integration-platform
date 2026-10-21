package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.support.DefaultExchange;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotFailureAssertions;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

public final class KafkaTriggerSnapshotRuntime implements AutoCloseable {
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private final Producer<byte[], byte[]> producer;
    private final String defaultTopic;
    private final BlockingQueue<Exchange> completions = new LinkedBlockingQueue<>();
    private final List<Exchange> received = new CopyOnWriteArrayList<>();
    private final List<Exchange> invocationCompletions = new CopyOnWriteArrayList<>();
    private final List<RecordId> allReceived = new CopyOnWriteArrayList<>();
    private final List<RecordId> allCompleted = new CopyOnWriteArrayList<>();
    private final List<Exchange> allInputs = new CopyOnWriteArrayList<>();
    private final Set<RecordId> redeliveryAllowed = ConcurrentHashMap.newKeySet();
    private final List<PublishedRecord> published = new ArrayList<>();
    private final List<PublishedRecord> invocationPublished = new ArrayList<>();
    private final List<PublishedRecord> startupRecords = new ArrayList<>();
    private volatile CompletableFuture<Throwable> consumerFailure = new CompletableFuture<>();
    private boolean allowInvocationRedelivery;
    private AfterPublish afterPublish = records -> { };

    @FunctionalInterface
    interface AfterPublish {
        void accept(List<RecordMetadata> records) throws Exception;
    }

    private record RecordId(String topic, int partition, long offset) {
        TopicPartition topicPartition() {
            return new TopicPartition(topic, partition);
        }
    }

    private record PublishedRecord(ProducerRecord<byte[], byte[]> input, RecordMetadata metadata,
                                   Object valueEncoding, Object keyEncoding) {
        RecordId id() {
            return new RecordId(metadata.topic(), metadata.partition(), metadata.offset());
        }
    }

    KafkaTriggerSnapshotRuntime(String bootstrapServers, List<String> topics, String groupId) {
        this(new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG, groupId + "-snapshot-producer",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 15000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000)), topics.getFirst());
    }

    KafkaTriggerSnapshotRuntime(Producer<byte[], byte[]> producer, String defaultTopic) {
        this.producer = producer;
        this.defaultTopic = defaultTopic;
    }

    public static String registryName(String nodeId) {
        return "snapshot-kafka-trigger:" + nodeId;
    }

    public Exchange send(SnapshotScenarioInvocation invocation, Map<String, Object> parameters) throws Exception {
        boolean consumeStartup = Boolean.TRUE.equals(parameters.get("consumeStartup"));
        List<PublishedRecord> current;
        if (consumeStartup) {
            current = List.copyOf(startupRecords);
            startupRecords.clear();
            assertFalse(current.isEmpty(), "Kafka trigger consumeStartup requires expectedStartupRecords.");
        } else {
            current = publishMessages(invocation.getBody(), invocation.getHeaders(), parameters);
            published.addAll(current);
        }
        invocationPublished.addAll(current);
        if (allowInvocationRedelivery) {
            current.forEach(record -> redeliveryAllowed.add(record.id()));
        }
        afterPublish.accept(current.stream().map(PublishedRecord::metadata).toList());
        Map<RecordId, PublishedRecord> expected = byId(current);
        Map<RecordId, Exchange> results = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (results.size() < current.size()) {
            Exchange result = completions.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(result, "Kafka trigger invocation '" + invocation.getId() + "' did not complete within 30 seconds.");
            RecordId id = id(result);
            assertTrue(expected.containsKey(id), "Kafka trigger completed an unexpected record: " + id);
            Exchange previous = results.putIfAbsent(id, result);
            assertTrue(previous == null || redeliveryAllowed.contains(id), "Kafka trigger completed the same record more than once: " + id);
        }
        List<Exchange> ordered = current.stream().map(record -> results.get(record.id())).toList();
        Exchange result = copy(ordered.getLast());
        if (consumeStartup || parameters.containsKey("messages") || parameters.containsKey("messageCount")) {
            result.getMessage().setBody(ordered.stream().map(exchange -> exchange.getMessage().getBody()).toList());
        }
        ordered.stream().filter(exchange -> exchange.getException() != null).findFirst()
                .ifPresent(failed -> result.setException(failed.getException()));
        return result;
    }

    void publishIgnored(List<?> messages) throws Exception {
        publishMessages(null, Map.of(), Map.of("messages", messages));
    }

    void publishStartup(List<?> messages) throws Exception {
        List<PublishedRecord> records = publishMessages(null, Map.of(), Map.of("messages", messages));
        startupRecords.addAll(records);
        published.addAll(records);
    }

    void consumerFailure(Throwable failure) {
        consumerFailure.complete(failure);
    }

    boolean hasConsumerFailure() {
        return consumerFailure.isDone();
    }

    Throwable getConsumerFailure() {
        return consumerFailure.getNow(null);
    }

    void clearConsumerFailure() {
        consumerFailure = new CompletableFuture<>();
    }

    public Exchange awaitConsumerFailure(CamelContext context) throws Exception {
        Exchange result = new DefaultExchange(context, ExchangePattern.InOnly);
        result.setException(consumerFailure.get(20, TimeUnit.SECONDS));
        return result;
    }

    private List<PublishedRecord> publishMessages(Object body, Map<String, Object> headers,
                                                  Map<String, Object> parameters) throws Exception {
        List<PublishedRecord> result = new ArrayList<>();
        if (parameters.get("messages") instanceof List<?> messages) {
            for (Object item : messages) {
                Map<?, ?> message = (Map<?, ?>) item;
                Map<String, Object> options = new LinkedHashMap<>(parameters);
                options.remove("messages");
                message.forEach((name, value) -> options.put((String) name, value));
                Map<String, Object> combinedHeaders = new LinkedHashMap<>(headers);
                if (message.get("headers") instanceof Map<?, ?> values) {
                    values.forEach((name, value) -> combinedHeaders.put((String) name, value));
                }
                publishRepeated(result, message.get("body"), combinedHeaders, options);
            }
        } else {
            publishRepeated(result, body, headers, parameters);
        }
        return result;
    }

    private void publishRepeated(List<PublishedRecord> result, Object body, Map<String, Object> headers,
                                 Map<String, Object> parameters) throws Exception {
        int count = ((Number) parameters.getOrDefault("messageCount", 1)).intValue();
        for (int index = 0; index < count; index++) {
            ProducerRecord<byte[], byte[]> record = createRecord(defaultTopic, body, headers, parameters);
            RecordMetadata metadata = producer.send(record).get(20, TimeUnit.SECONDS);
            result.add(new PublishedRecord(record, metadata, parameters.get("valueEncoding"), parameters.get("keyEncoding")));
        }
    }

    static ProducerRecord<byte[], byte[]> createRecord(String defaultTopic, Object body, Map<String, Object> headers,
                                                       Map<String, Object> parameters) {
        RecordHeaders recordHeaders = new RecordHeaders();
        headers.forEach((key, value) -> recordHeaders.add(key, utf8(value)));
        for (Object item : (List<?>) parameters.getOrDefault("recordHeaders", List.of())) {
            Map<?, ?> header = (Map<?, ?>) item;
            recordHeaders.add((String) header.get("key"), utf8(header.get("value")));
        }
        Integer partition = parameters.get("partition") instanceof Number value ? value.intValue() : null;
        Long timestamp = parameters.get("timestamp") instanceof Number value ? value.longValue() : null;
        return new ProducerRecord<>((String) parameters.getOrDefault("topic", defaultTopic), partition, timestamp,
                KafkaTriggerSnapshotRecordCodec.encode(parameters.get("key"), parameters.get("keyEncoding")),
                KafkaTriggerSnapshotRecordCodec.encode(body, parameters.get("valueEncoding")), recordHeaders);
    }

    private static byte[] utf8(Object value) {
        return value == null ? null : ((String) value).getBytes(StandardCharsets.UTF_8);
    }

    void afterPublish(AfterPublish callback) {
        afterPublish = callback;
    }

    Map<TopicPartition, Long> publishedOffsets() {
        Map<TopicPartition, Long> offsets = new LinkedHashMap<>();
        published.forEach(record -> offsets.merge(record.id().topicPartition(), record.id().offset() + 1, Math::max));
        return offsets;
    }

    void assertNothingDelivered() {
        assertTrue(received.isEmpty(), "Kafka trigger received a record while its consumer was unavailable.");
        assertTrue(completions.isEmpty(), "Kafka trigger completed a record while its consumer was unavailable.");
    }

    void received(Exchange exchange) {
        assertEquals(ExchangePattern.InOnly, exchange.getPattern(), "Kafka trigger must consume records as InOnly.");
        Exchange snapshot = copy(exchange);
        received.add(snapshot);
        allInputs.add(snapshot);
        allReceived.add(id(exchange));
    }

    void completed(Exchange exchange) {
        Exchange snapshot = copy(exchange);
        if (snapshot.getException() == null && !exchange.getExchangeExtension().isFailureHandled()) {
            snapshot.setException(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class));
        }
        invocationCompletions.add(snapshot);
        allCompleted.add(id(snapshot));
        completions.add(snapshot);
    }

    private static Exchange copy(Exchange exchange) {
        Exchange snapshot = new DefaultExchange(exchange.getContext(), exchange.getPattern());
        snapshot.getMessage().setBody(exchange.getMessage().getBody());
        snapshot.getMessage().setHeaders(new LinkedHashMap<>(exchange.getMessage().getHeaders()));
        Object rawHeaders = exchange.getMessage().getHeader(KafkaConstants.HEADERS);
        if (rawHeaders instanceof Headers values) {
            RecordHeaders headers = new RecordHeaders();
            values.forEach(header -> headers.add(header.key(), header.value() == null ? null : header.value().clone()));
            snapshot.getMessage().setHeader(KafkaConstants.HEADERS, headers);
        }
        exchange.getAllProperties().forEach(snapshot::setProperty);
        snapshot.setException(exchange.getException());
        return snapshot;
    }

    void beforeInvocation(Map<String, Object> controls) {
        allowInvocationRedelivery = Boolean.TRUE.equals(controls.get("allowRedelivery"));
        if (startupRecords.isEmpty()) {
            assertNoUnexpectedCompletions("Kafka trigger");
            received.clear();
            invocationCompletions.clear();
        }
        invocationPublished.clear();
        afterPublish = records -> { };
    }

    void verifyInvocation(SnapshotFixtureInteraction interaction, String invocationId) {
        String description = "Kafka trigger invocation '" + invocationId + "'";
        SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
        assertNoUnexpectedCompletions(description);
        assertEquals(expected.getCount(), uniqueExchanges(received).size(), description + " received record count");
        assertEquals(invocationPublished.size(), uniqueExchanges(invocationCompletions).size(), description + " completion count");
        assertRecordSequence(invocationPublished.stream().map(PublishedRecord::id).toList(),
                received.stream().map(KafkaTriggerSnapshotRuntime::id).toList(), description + " input");
        assertRecordSequence(invocationPublished.stream().map(PublishedRecord::id).toList(),
                invocationCompletions.stream().map(KafkaTriggerSnapshotRuntime::id).toList(), description + " completion");
        Map<RecordId, PublishedRecord> records = byId(invocationPublished);
        Map<String, Object> controls = interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
        for (Exchange input : received) {
            assertInput(records.get(id(input)), input, description);
            if (expected.hasBody()) {
                assertEquals(expected.getBody(), input.getMessage().getBody(), description + " body");
            }
            if (expected.getDestination() != null) {
                assertEquals(expected.getDestination(), id(input).topic(), description + " topic");
            }
            if (expected.getKey() != null) {
                assertEquals(expected.getKey(), input.getMessage().getHeader(KafkaConstants.KEY), description + " key");
            }
            SnapshotValueAssertions.assertMapValues(expected.getHeaders(), input.getMessage().getHeaders(), description + " header");
            SnapshotValueAssertions.assertMapValues(expected.getProperties(), input.getAllProperties(), description + " property");
            for (Object name : (List<?>) controls.getOrDefault("expectedAbsentHeaders", List.of())) {
                assertFalse(input.getMessage().getHeaders().containsKey(name), description + " must omit header '" + name + "'.");
            }
        }
        if (interaction.hasExpectedExchanges()) {
            assertExchanges(expand(interaction.getExpectedExchanges()), uniqueExchanges(received), description + " input", false);
        }
        if (controls.get("expectedCompletions") instanceof List<?> values) {
            List<SnapshotFixtureExchangeExpectation> expectations = values.stream()
                    .map(value -> MAPPER.convertValue(value, SnapshotFixtureExchangeExpectation.class)).toList();
            assertExchanges(expand(expectations), uniqueExchanges(invocationCompletions), description + " completion", true);
        }
        for (Exchange completion : invocationCompletions) {
            if (controls.containsKey("expectedCompletionBody")) {
                assertEquals(controls.get("expectedCompletionBody"), completion.getMessage().getBody(), description + " completion body");
            }
            assertCompletionValues(controls, "expectedCompletionHeaders", completion.getMessage().getHeaders(), description);
            assertCompletionValues(controls, "expectedCompletionProperties", completion.getAllProperties(), description);
        }
    }

    private static void assertInput(PublishedRecord record, Exchange input, String description) {
        assertNotNull(record, description + " received an unpublished record.");
        assertTypedValue(record.input().value(), input.getMessage().getBody(), record.valueEncoding(), description + " published body");
        assertTypedValue(record.input().key(), input.getMessage().getHeader(KafkaConstants.KEY), record.keyEncoding(),
                description + " published key");
        assertEquals(record.metadata().timestamp(), input.getMessage().getHeader(KafkaConstants.TIMESTAMP), description + " timestamp");
        assertEquals(record.metadata().timestamp(), input.getMessage().getHeader(Exchange.MESSAGE_TIMESTAMP), description + " message timestamp");
        Headers raw = assertInstanceOf(Headers.class, input.getMessage().getHeader(KafkaConstants.HEADERS));
        Header[] expected = record.input().headers().toArray();
        Header[] actual = raw.toArray();
        assertEquals(expected.length, actual.length, description + " raw header count");
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index].key(), actual[index].key(), description + " raw header name " + index);
            assertArrayEquals(expected[index].value(), actual[index].value(), description + " raw header value " + index);
        }
    }

    private static void assertTypedValue(byte[] expected, Object actual, Object encoding, String description) {
        if (expected == null) {
            assertNull(actual, description);
            return;
        }
        assertNotNull(actual, description);
        assertEquals(KafkaTriggerSnapshotRecordCodec.valueType(encoding), actual.getClass(), description + " type");
        assertArrayEquals(expected, KafkaTriggerSnapshotRecordCodec.encode(actual, encoding), description + " bytes");
    }

    private static void assertExchanges(List<SnapshotFixtureExchangeExpectation> expectations, List<Exchange> exchanges,
                                       String description, boolean checkFailures) {
        assertEquals(expectations.size(), exchanges.size(), description + " expected exchange count");
        for (int index = 0; index < expectations.size(); index++) {
            SnapshotFixtureExchangeExpectation expected = expectations.get(index);
            Exchange actual = exchanges.get(index);
            if (expected.hasBody()) {
                assertEquals(expected.getBody(), actual.getMessage().getBody(), description + " body " + index);
            }
            SnapshotValueAssertions.assertMapValues(expected.getHeaders(), actual.getMessage().getHeaders(), description + " header " + index);
            SnapshotValueAssertions.assertMapValues(expected.getProperties(), actual.getAllProperties(), description + " property " + index);
            if (checkFailures) {
                SnapshotFailureAssertions.assertMatches(expected.getExpectedFailure(), actual.getException(), description + " " + index);
            }
        }
    }

    private static List<SnapshotFixtureExchangeExpectation> expand(List<SnapshotFixtureExchangeExpectation> expectations) {
        List<SnapshotFixtureExchangeExpectation> expanded = new ArrayList<>();
        for (SnapshotFixtureExchangeExpectation expectation : expectations) {
            for (int index = 0; index < expectation.getCount(); index++) {
                expanded.add(expectation);
            }
        }
        return expanded;
    }

    private static void assertCompletionValues(Map<String, Object> controls, String key, Map<String, Object> actual,
                                               String description) {
        if (controls.get(key) instanceof Map<?, ?> values) {
            Map<String, Object> expected = new LinkedHashMap<>();
            values.forEach((name, value) -> expected.put((String) name, value));
            SnapshotValueAssertions.assertMapValues(expected, actual, description + " completion " + key);
        }
    }

    private static RecordId id(Exchange exchange) {
        return new RecordId(exchange.getMessage().getHeader(KafkaConstants.TOPIC, String.class),
                exchange.getMessage().getHeader(KafkaConstants.PARTITION, Integer.class),
                exchange.getMessage().getHeader(KafkaConstants.OFFSET, Long.class));
    }

    private static Map<RecordId, PublishedRecord> byId(List<PublishedRecord> records) {
        Map<RecordId, PublishedRecord> result = new LinkedHashMap<>();
        records.forEach(record -> result.put(record.id(), record));
        return result;
    }

    private void assertRecordSequence(List<RecordId> expected, List<RecordId> actual, String description) {
        Map<TopicPartition, Long> previous = new LinkedHashMap<>();
        Set<RecordId> seen = new HashSet<>();
        List<RecordId> unique = new ArrayList<>();
        for (RecordId record : actual) {
            Long last = previous.put(record.topicPartition(), record.offset());
            assertTrue(last == null || record.offset() > last
                    || record.offset() == last && redeliveryAllowed.contains(record), description + " partition order");
            if (seen.add(record)) {
                unique.add(record);
            } else {
                assertTrue(redeliveryAllowed.contains(record), description + " delivered a duplicate record: " + record);
            }
        }
        Map<TopicPartition, List<Long>> expectedOffsets = partitionOffsets(expected);
        Map<TopicPartition, List<Long>> actualOffsets = partitionOffsets(unique);
        assertEquals(expectedOffsets, actualOffsets, description + " must contain each record once, in partition order.");
    }

    private static Map<TopicPartition, List<Long>> partitionOffsets(List<RecordId> records) {
        Map<TopicPartition, List<Long>> offsets = new LinkedHashMap<>();
        records.forEach(record -> offsets.computeIfAbsent(record.topicPartition(), ignored -> new ArrayList<>()).add(record.offset()));
        return offsets;
    }

    void verifyAfterConsumerStopped() {
        List<RecordId> expected = published.stream().map(PublishedRecord::id).toList();
        assertRecordSequence(expected, allReceived, "Kafka trigger final input");
        assertRecordSequence(expected, allCompleted, "Kafka trigger final completion");
        Map<RecordId, PublishedRecord> records = byId(published);
        allInputs.forEach(input -> assertInput(records.get(id(input)), input, "Kafka trigger final input"));
        assertNoUnexpectedCompletions("Kafka trigger");
    }

    private void assertNoUnexpectedCompletions(String description) {
        Exchange pending;
        while ((pending = completions.peek()) != null) {
            assertTrue(redeliveryAllowed.contains(id(pending)), description + " completed an unexpected extra record.");
            completions.poll();
        }
    }

    private static List<Exchange> uniqueExchanges(List<Exchange> exchanges) {
        Map<RecordId, Exchange> unique = new LinkedHashMap<>();
        exchanges.forEach(exchange -> unique.putIfAbsent(id(exchange), exchange));
        return List.copyOf(unique.values());
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
