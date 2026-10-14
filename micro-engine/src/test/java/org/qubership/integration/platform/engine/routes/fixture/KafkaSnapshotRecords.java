package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class KafkaSnapshotRecords {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper OBJECT_MAPPER = ObjectMappers.getObjectMapper();

    private KafkaSnapshotRecords() {
    }

    static List<ConsumerRecord<byte[], byte[]>> read(Properties connectionProperties, Set<String> topics, int expectedCount) {
        if (topics.isEmpty()) {
            return List.of();
        }
        Properties properties = new Properties();
        properties.putAll(connectionProperties);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = topics.stream()
                    .flatMap(topic -> consumer.partitionsFor(topic, TIMEOUT).stream())
                    .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                    .toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, TIMEOUT);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while ((records.size() < expectedCount || !atEnd(consumer, ends)) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                ends = consumer.endOffsets(partitions, TIMEOUT);
            }
            assertTrue(atEnd(consumer, ends), "Timed out while reading the Kafka snapshot backlog.");
            // The producer has completed; a second offset read also detects publications arriving during the drain.
            assertEquals(ends, consumer.endOffsets(partitions, TIMEOUT), "Kafka received an unexpected late publication.");
            return List.copyOf(records);
        }
    }

    private static boolean atEnd(KafkaConsumer<byte[], byte[]> consumer, Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream().allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    static Map<String, Object> mergeExpectation(Map<String, Object> inherited, Map<?, ?> fields) {
        if (!Set.of("topic", "key", "keyHex", "body", "bodyHex", "bodyRepeat", "headers", "headersHex", "partition", "offset", "timestamp")
                .containsAll(fields.keySet())) {
            throw new IllegalArgumentException("Kafka expectedRecords contains an unsupported field.");
        }
        Map<String, Object> result = new LinkedHashMap<>(inherited);
        for (String field : List.of("key", "body")) {
            String hexField = field + "Hex";
            if (fields.containsKey(hexField)) {
                if (fields.containsKey(field)) {
                    throw new IllegalArgumentException("Kafka expectedRecords cannot specify both " + field + " and " + hexField + '.');
                }
                hexadecimalBytes(fields.get(hexField), hexField);
                result.remove(field);
            }
        }
        fields.forEach((name, value) -> result.put((String) name, value));
        if (result.containsKey("headersHex")) {
            if (!(result.get("headersHex") instanceof Map<?, ?> headersHex)) {
                throw new IllegalArgumentException("Kafka expectedRecords headersHex must be a map of header names to hexadecimal strings.");
            }
            headersHex.forEach((name, value) -> hexadecimalBytes(value, "headersHex." + name));
        }
        if (result.containsKey("bodyRepeat") && (!(result.get("bodyRepeat") instanceof Integer repeat) || repeat <= 0
                || !(result.get("body") instanceof String))) {
            throw new IllegalArgumentException("Kafka expectedRecords bodyRepeat requires a positive integer and a string body.");
        }
        return Collections.unmodifiableMap(result);
    }

    static void assertRecords(
            List<Map<String, Object>> expected,
            List<ConsumerRecord<byte[], byte[]>> actual,
            String description
    ) {
        assertEquals(expected.size(), actual.size(), description + " delivered an unexpected number of records.");
        Map<TopicPartition, Deque<ConsumerRecord<byte[], byte[]>>> partitions = new LinkedHashMap<>();
        for (ConsumerRecord<byte[], byte[]> record : actual) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            Deque<ConsumerRecord<byte[], byte[]>> records = partitions.computeIfAbsent(partition, ignored -> new ArrayDeque<>());
            if (!records.isEmpty()) {
                assertTrue(records.getLast().offset() < record.offset(), description + " returned offsets out of order.");
            }
            records.add(record);
        }
        for (int index = 0; index < expected.size(); index++) {
            Map<String, Object> expectation = expected.get(index);
            Deque<ConsumerRecord<byte[], byte[]>> matchingPartition = null;
            AssertionError mismatch = null;
            for (Deque<ConsumerRecord<byte[], byte[]>> records : partitions.values()) {
                if (records.isEmpty()) {
                    continue;
                }
                try {
                    assertRecord(expectation, records.getFirst(), description + " record " + (index + 1));
                    matchingPartition = records;
                    break;
                } catch (AssertionError exception) {
                    mismatch = exception;
                }
            }
            if (matchingPartition == null) {
                fail(description + " has no matching next record in any partition for " + expectation, mismatch);
            }
            matchingPartition.removeFirst();
        }
    }

    private static void assertRecord(Map<String, Object> expected, ConsumerRecord<byte[], byte[]> actual, String description) {
        assertEquals(expected.get("topic"), actual.topic(), description + " topic");
        if (expected.containsKey("keyHex")) {
            assertArrayEquals(hexadecimalBytes(expected.get("keyHex"), "keyHex"), actual.key(), description + " key");
        } else if (expected.containsKey("key")) {
            assertArrayEquals(bytes(expected.get("key")), actual.key(), description + " key");
        }
        if (expected.containsKey("bodyHex")) {
            assertArrayEquals(hexadecimalBytes(expected.get("bodyHex"), "bodyHex"), actual.value(), description + " body");
        } else if (expected.containsKey("body")) {
            Object body = expected.get("body");
            if (expected.get("bodyRepeat") instanceof Integer repeat) {
                body = ((String) body).repeat(repeat);
            }
            if (body == null || body instanceof String || body instanceof byte[]) {
                assertArrayEquals(bytes(body), actual.value(), description + " body");
            } else {
                assertNotNull(actual.value(), description + " body");
                try {
                    assertEquals(OBJECT_MAPPER.valueToTree(body), OBJECT_MAPPER.readTree(actual.value()), description + " JSON body");
                } catch (IOException exception) {
                    fail(description + " body is not valid JSON.", exception);
                }
            }
        }
        assertNumber(expected, "partition", actual.partition(), description);
        assertNumber(expected, "offset", actual.offset(), description);
        assertNumber(expected, "timestamp", actual.timestamp(), description);
        if (expected.get("headers") instanceof Map<?, ?> headers) {
            Map<String, List<byte[]>> actualHeaders = new LinkedHashMap<>();
            for (Header header : actual.headers()) {
                actualHeaders.computeIfAbsent(header.key(), ignored -> new ArrayList<>()).add(header.value());
            }
            headers.forEach((name, value) -> {
                if (value == null) {
                    assertFalse(actualHeaders.containsKey(name), description + " unexpected header " + name);
                } else {
                    List<?> values = value instanceof List<?> list ? list : List.of(value);
                    List<byte[]> received = actualHeaders.get(name);
                    assertNotNull(received, description + " missing header " + name);
                    assertEquals(values.size(), received.size(), description + " header count " + name);
                    for (int index = 0; index < values.size(); index++) {
                        assertArrayEquals(bytes(values.get(index)), received.get(index), description + " header " + name);
                    }
                }
            });
        }
        if (expected.get("headersHex") instanceof Map<?, ?> headersHex) {
            headersHex.forEach((name, value) -> {
                List<Header> headers = new ArrayList<>();
                actual.headers().headers((String) name).forEach(headers::add);
                assertEquals(1, headers.size(), description + " header count " + name);
                assertArrayEquals(hexadecimalBytes(value, "headersHex." + name), headers.getFirst().value(),
                        description + " header " + name);
            });
        }
    }

    static void assertNoMetadata(Exchange exchange) {
        assertNull(exchange.getMessage().getHeader(KafkaConstants.KAFKA_RECORD_META),
                "Kafka returned record metadata while recordMetadata was disabled.");
        if (exchange.getProperty("snapshot.kafkaBatchItems") instanceof List<?> items) {
            for (Object item : items) {
                Message message = item instanceof Exchange inner ? inner.getMessage() : item instanceof Message inner ? inner : null;
                if (message != null) {
                    assertNull(message.getHeader(KafkaConstants.KAFKA_RECORD_META),
                            "Kafka returned batch item metadata while recordMetadata was disabled.");
                }
            }
        }
    }

    static void assertMetadata(
            List<Exchange> exchanges,
            int expectedCount,
            List<ConsumerRecord<byte[], byte[]>> records
    ) {
        List<RecordMetadata> metadata = new ArrayList<>();
        for (Exchange exchange : exchanges) {
            Object value = exchange.getMessage().getHeader(KafkaConstants.KAFKA_RECORD_META);
            Object batchItems = exchange.getProperty("snapshot.kafkaBatchItems");
            Object body = batchItems == null ? exchange.getMessage().getBody() : batchItems;
            if (value == null && body instanceof List<?> list && list.isEmpty()) {
                continue;
            }
            if (exchange.getException() != null) {
                assertTrue(value == null || value instanceof List<?> list && list.isEmpty(),
                        "A failed Kafka publication returned successful record metadata.");
                continue;
            }
            List<?> values = assertInstanceOf(List.class, value, "Kafka did not return record metadata.");
            values.forEach(item -> metadata.add(assertInstanceOf(RecordMetadata.class, item)));
            if (batchItems instanceof List<?> items) {
                for (Object item : items) {
                    Message message = item instanceof Exchange inner ? inner.getMessage() : item instanceof Message inner ? inner : null;
                    if (message != null) {
                        List<?> innerMetadata = assertInstanceOf(List.class, message.getHeader(KafkaConstants.KAFKA_RECORD_META),
                                "Kafka did not return metadata on a batch item.");
                        assertEquals(1, innerMetadata.size(), "Kafka returned an unexpected metadata count on a batch item.");
                        RecordMetadata entry = assertInstanceOf(RecordMetadata.class, innerMetadata.getFirst());
                        assertTrue(values.contains(entry), "Kafka batch item metadata is missing from the outer exchange.");
                        ConsumerRecord<byte[], byte[]> record = findRecord(entry, records);
                        Map<String, Object> expected = new LinkedHashMap<>();
                        expected.put("topic", record.topic());
                        expected.put("body", message.getBody());
                        assertRecord(expected, record, "Kafka batch item metadata");
                    }
                }
            }
        }
        assertEquals(expectedCount, metadata.size(), "Kafka returned an unexpected number of record metadata entries.");
        for (RecordMetadata item : metadata) {
            ConsumerRecord<byte[], byte[]> record = findRecord(item, records);
            assertEquals(record.serializedKeySize(), item.serializedKeySize(), "Kafka metadata key size");
            assertEquals(record.serializedValueSize(), item.serializedValueSize(), "Kafka metadata value size");
        }
    }

    private static ConsumerRecord<byte[], byte[]> findRecord(RecordMetadata metadata, List<ConsumerRecord<byte[], byte[]>> records) {
        return records.stream()
                .filter(record -> record.topic().equals(metadata.topic()) && record.partition() == metadata.partition()
                        && record.offset() == metadata.offset() && record.timestamp() == metadata.timestamp())
                .findFirst().orElseThrow(() -> new AssertionError("Kafka metadata does not identify a consumed record: " + metadata));
    }

    private static void assertNumber(Map<String, Object> expected, String name, long actual, String description) {
        if (expected.containsKey(name)) {
            assertEquals(((Number) expected.get(name)).longValue(), actual, description + ' ' + name);
        }
    }

    private static byte[] bytes(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof byte[] data ? data : value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hexadecimalBytes(Object value, String field) {
        if (!(value instanceof String hexadecimal)) {
            throw new IllegalArgumentException("Kafka expectedRecords " + field + " must be a hexadecimal string.");
        }
        try {
            return HexFormat.of().parseHex(hexadecimal);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Kafka expectedRecords " + field + " must contain complete hexadecimal byte pairs.", exception);
        }
    }
}
