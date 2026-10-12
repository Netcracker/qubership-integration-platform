package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.Exchange;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaSnapshotRecordsTest {
    private static final String TOPIC = "serializer-topic";

    @ParameterizedTest
    @ValueSource(strings = {"outer", "message", "exchange"})
    void shouldRejectMetadataAtAnyBatchLevelWhenRecordMetadataIsDisabled(String location) {
        Exchange outer = MockExchanges.defaultExchange();
        Exchange message = MockExchanges.defaultExchange();
        Exchange nested = MockExchanges.defaultExchange();
        outer.setProperty("snapshot.kafkaBatchItems", List.of(message.getMessage(), nested, "plain-value"));

        KafkaSnapshotRecords.assertNoMetadata(outer);

        Exchange target = switch (location) {
            case "message" -> message;
            case "exchange" -> nested;
            default -> outer;
        };
        target.getMessage().setHeader(KafkaConstants.KAFKA_RECORD_META, List.of());

        assertThrows(AssertionError.class, () -> KafkaSnapshotRecords.assertNoMetadata(outer));
    }

    @Test
    void shouldMatchExactHeaderBytesWhenHeaderValuesAreBinaryOrNumeric() {
        Map<String, Object> expected = KafkaSnapshotRecords.mergeExpectation(Map.of("topic", TOPIC), Map.of(
                "headers", Map.of("X-Text", "café"),
                "headersHex", Map.of("X-Binary", "00ff80", "X-Number", "0000002a", "X-Empty", "")
        ));
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, null);
        record.headers().add("X-Text", "café".getBytes(StandardCharsets.UTF_8));
        record.headers().add("X-Binary", new byte[] {0, -1, -128});
        record.headers().add("X-Number", integerBytes(42));
        record.headers().add("X-Empty", new byte[0]);

        KafkaSnapshotRecords.assertRecords(List.of(expected), List.of(record), "Typed headers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-bytes", "duplicate", "null"})
    void shouldRejectIncorrectHeadersWhenHexRequiresExactBytesAndOneValue(String mismatch) {
        Map<String, Object> expected = KafkaSnapshotRecords.mergeExpectation(
                Map.of("topic", TOPIC), Map.of("headersHex", Map.of("X-Binary", "00ff"))
        );
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, null);
        switch (mismatch) {
            case "wrong-bytes" -> record.headers().add("X-Binary", new byte[] {0, -2});
            case "duplicate" -> {
                record.headers().add("X-Binary", new byte[] {0, -1});
                record.headers().add("X-Binary", new byte[] {0, -1});
            }
            case "null" -> record.headers().add("X-Binary", null);
            default -> { }
        }

        assertThrows(AssertionError.class, () -> KafkaSnapshotRecords.assertRecords(List.of(expected), List.of(record), "Typed headers"));
    }

    @Test
    void shouldCheckMetadataOnEachBatchItemWhenBodyContainsMessagesAndExchanges() {
        Exchange outer = MockExchanges.defaultExchange();
        Exchange first = MockExchanges.defaultExchange();
        Exchange second = MockExchanges.defaultExchange();
        first.getMessage().setBody("first");
        second.getMessage().setBody("second");
        RecordMetadata firstMetadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0, 0, -1, -1, -1);
        RecordMetadata secondMetadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0, 1, -1, -1, -1);
        first.getMessage().setHeader(KafkaConstants.KAFKA_RECORD_META, List.of(firstMetadata));
        second.getMessage().setHeader(KafkaConstants.KAFKA_RECORD_META, List.of(secondMetadata));
        outer.getMessage().setHeader(KafkaConstants.KAFKA_RECORD_META, List.of(firstMetadata, secondMetadata));
        outer.setProperty("snapshot.kafkaBatchItems", List.of(first.getMessage(), second));
        List<ConsumerRecord<byte[], byte[]>> records = List.of(
                new ConsumerRecord<>(TOPIC, 0, 0, null, "first".getBytes(StandardCharsets.UTF_8)),
                new ConsumerRecord<>(TOPIC, 0, 1, null, "second".getBytes(StandardCharsets.UTF_8))
        );

        KafkaSnapshotRecords.assertMetadata(List.of(outer), 2, records);

        first.getMessage().setHeader(KafkaConstants.KAFKA_RECORD_META, List.of(secondMetadata));
        assertThrows(AssertionError.class, () -> KafkaSnapshotRecords.assertMetadata(List.of(outer), 2, records));
        first.getMessage().removeHeader(KafkaConstants.KAFKA_RECORD_META);
        assertThrows(AssertionError.class, () -> KafkaSnapshotRecords.assertMetadata(List.of(outer), 2, records));
    }

    @Test
    void shouldAcceptAbsentMetadataWhenAnIteratorContainsNoItems() {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.getMessage().setBody(Collections.emptyIterator());
        exchange.setProperty("snapshot.kafkaBatchItems", List.of());

        KafkaSnapshotRecords.assertMetadata(List.of(exchange), 0, List.of());
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 00000000, 0000000000000000",
            "42, 42, 0000002a, 000000000000002a",
            "-42, -42, ffffffd6, ffffffffffffffd6",
            "-2147483648, -9223372036854775808, 80000000, 8000000000000000",
            "2147483647, 9223372036854775807, 7fffffff, 7fffffffffffffff"
    })
    void shouldMatchRawNumericBytesWhenHexSpecifiesIntegerKeyAndLongBody(int key, long body, String keyHex, String bodyHex) {
        assertRecord(Map.of("keyHex", keyHex, "bodyHex", bodyHex), integerBytes(key), longBytes(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3432", "2a", "0000002b", "2a000000", "000000000000002a"})
    void shouldRejectIncorrectKeyBytesWhenHexRequiresIntegerEncoding(String receivedHex) {
        assertThrows(AssertionError.class, () -> assertRecord(
                Map.of("keyHex", "0000002a", "bodyHex", "000000000000002a"),
                HexFormat.of().parseHex(receivedHex), longBytes(42)
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3432", "2a", "0000002a", "000000000000002b", "2a00000000000000"})
    void shouldRejectIncorrectBodyBytesWhenHexRequiresLongEncoding(String receivedHex) {
        assertThrows(AssertionError.class, () -> assertRecord(
                Map.of("keyHex", "0000002a", "bodyHex", "000000000000002a"),
                integerBytes(42), HexFormat.of().parseHex(receivedHex)
        ));
    }

    @Test
    void shouldDistinguishNullFromBytesWhenRecordsContainTombstonesOrEmptyValues() {
        Map<String, Object> nulls = KafkaSnapshotRecords.mergeExpectation(
                Collections.singletonMap("key", null), Collections.singletonMap("body", null)
        );
        Map<String, Object> empty = Map.of("keyHex", "", "bodyHex", "");
        Map<String, Object> numeric = Map.of("keyHex", "0000002a", "bodyHex", "000000000000002a");

        assertRecord(nulls, null, null);
        assertRecord(empty, new byte[0], new byte[0]);
        assertThrows(AssertionError.class, () -> assertRecord(nulls, new byte[0], null));
        assertThrows(AssertionError.class, () -> assertRecord(nulls, null, new byte[0]));
        assertThrows(AssertionError.class, () -> assertRecord(empty, null, new byte[0]));
        assertThrows(AssertionError.class, () -> assertRecord(empty, new byte[0], null));
        assertThrows(AssertionError.class, () -> assertRecord(numeric, null, longBytes(42)));
        assertThrows(AssertionError.class, () -> assertRecord(numeric, integerBytes(42), null));
    }

    @Test
    void shouldReplaceInheritedValuesWhenRecordSpecifiesHex() {
        Map<String, Object> expectation = KafkaSnapshotRecords.mergeExpectation(
                Map.of("topic", TOPIC, "key", "inherited-key", "body", "inherited-body"),
                Map.of("keyHex", "0000002A", "bodyHex", "000000000000002A")
        );

        assertFalse(expectation.containsKey("key"));
        assertFalse(expectation.containsKey("body"));
        assertRecord(expectation, integerBytes(42), longBytes(42));
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "body"})
    void shouldRejectAmbiguousExpectationWhenRecordSpecifiesBothValueAndHex(String field) {
        assertThrows(IllegalArgumentException.class, () -> KafkaSnapshotRecords.mergeExpectation(
                Map.of("topic", TOPIC), Map.of(field, "42", field + "Hex", "3432")
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "xyz", "0x2a", "00 2a"})
    void shouldRejectMalformedHexWhenRecordExpectationIsMerged(String hexadecimal) {
        assertThrows(IllegalArgumentException.class, () -> KafkaSnapshotRecords.mergeExpectation(
                Map.of("topic", TOPIC), Map.of("keyHex", hexadecimal)
        ));
    }

    @ParameterizedTest
    @MethodSource("invalidHexTypes")
    void shouldRejectNonStringHexWhenRecordExpectationIsMerged(Object hexadecimal) {
        assertThrows(IllegalArgumentException.class, () -> KafkaSnapshotRecords.mergeExpectation(
                Map.of("topic", TOPIC), Collections.singletonMap("bodyHex", hexadecimal)
        ));
    }

    @ParameterizedTest
    @MethodSource("existingBodyExpectations")
    void shouldPreserveExistingBodyAssertionsWhenHexIsAbsent(Object body, byte[] received) {
        assertRecord(Map.of("key", "request-key", "body", body), "request-key".getBytes(StandardCharsets.UTF_8), received);
    }

    private static Stream<Arguments> invalidHexTypes() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(42), Arguments.of((Object) new byte[] {42}));
    }

    private static Stream<Arguments> existingBodyExpectations() {
        return Stream.of(
                Arguments.of("café", "café".getBytes(StandardCharsets.UTF_8)),
                Arguments.of(new byte[] {0, 42, -1}, new byte[] {0, 42, -1}),
                Arguments.of(Map.of("value", 42), "{ \"value\": 42 }".getBytes(StandardCharsets.UTF_8))
        );
    }

    private static void assertRecord(Map<String, Object> fields, byte[] key, byte[] body) {
        Map<String, Object> expected = KafkaSnapshotRecords.mergeExpectation(Map.of("topic", TOPIC), fields);
        ConsumerRecord<byte[], byte[]> received = new ConsumerRecord<>(TOPIC, 0, 0, key, body);
        KafkaSnapshotRecords.assertRecords(List.of(expected), List.of(received), "Numeric serialization");
    }

    private static byte[] integerBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
}
