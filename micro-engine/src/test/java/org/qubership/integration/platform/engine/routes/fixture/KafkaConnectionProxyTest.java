package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaConnectionProxyTest {
    private final KafkaConnectionProxy proxy = new KafkaConnectionProxy();

    @AfterEach
    void closeProxy() throws IOException {
        proxy.close();
    }

    @ParameterizedTest
    @CsvSource({"8, -1", "8, 0", "8, 1", "9, -1", "9, 0", "9, 1"})
    void shouldObserveRequiredAcksWhenProduceRequestUsesEitherHeaderFormat(short version, short requiredAcks) {
        byte[] frame = produceFrame(version, requiredAcks, Map.of("acks-topic", List.of(CompressionType.NONE)));

        assertEquals(requiredAcks, proxy.observeProduceRequest(frame));

        KafkaConnectionProxy.Statistics statistics = proxy.statistics();
        assertEquals(1, statistics.produceAttempts());
        assertEquals(0, statistics.forwardedProduceRequests());
        assertEquals(Map.of(requiredAcks, 1L), statistics.produceRequestsByAcks());
        assertEquals(Map.of("none", 1L), statistics.recordBatchesByCompression());
    }

    @ParameterizedTest
    @EnumSource(CompressionType.class)
    void shouldObserveCompressionWhenBatchContainsMultipleRecords(CompressionType codec) {
        byte[] frame = produceFrame((short) 9, (short) -1, Map.of("compression-topic", List.of(codec)));

        proxy.observeProduceRequest(frame);

        assertEquals(Map.of(codec.name, 1L), proxy.statistics().recordBatchesByCompression());
    }

    @Test
    void shouldCountEveryBatchWhenRequestContainsMultipleTopicsAndPartitions() {
        byte[] frame = produceFrame((short) 9, (short) 1, Map.of(
                "first-topic", List.of(CompressionType.GZIP, CompressionType.SNAPPY),
                "second-topic", List.of(CompressionType.GZIP)
        ));

        proxy.observeProduceRequest(frame);

        KafkaConnectionProxy.Statistics statistics = proxy.statistics();
        assertEquals(1, statistics.produceAttempts());
        assertEquals(Map.of((short) 1, 1L), statistics.produceRequestsByAcks());
        assertEquals(Map.of("gzip", 2L, "snappy", 1L), statistics.recordBatchesByCompression());
    }

    @Test
    void shouldPreserveStatisticsSnapshotWhenMoreRequestsArrive() {
        proxy.observeProduceRequest(produceFrame((short) 9, (short) -1,
                Map.of("first-topic", List.of(CompressionType.GZIP))));
        KafkaConnectionProxy.Statistics baseline = proxy.statistics();

        proxy.observeProduceRequest(produceFrame((short) 9, (short) 0,
                Map.of("second-topic", List.of(CompressionType.LZ4))));
        KafkaConnectionProxy.Statistics current = proxy.statistics();

        assertEquals(1, baseline.produceAttempts());
        assertEquals(Map.of((short) -1, 1L), baseline.produceRequestsByAcks());
        assertEquals(Map.of("gzip", 1L), baseline.recordBatchesByCompression());
        assertEquals(2, current.produceAttempts());
        assertEquals(Map.of((short) -1, 1L, (short) 0, 1L), current.produceRequestsByAcks());
        assertEquals(Map.of("gzip", 1L, "lz4", 1L), current.recordBatchesByCompression());
    }

    private static byte[] produceFrame(short version, short requiredAcks,
                                       Map<String, List<CompressionType>> compressionByTopic) {
        ProduceRequestData.TopicProduceDataCollection topics = new ProduceRequestData.TopicProduceDataCollection();
        compressionByTopic.forEach((topic, codecs) -> {
            List<ProduceRequestData.PartitionProduceData> partitions = new ArrayList<>();
            for (int index = 0; index < codecs.size(); index++) {
                MemoryRecords records = MemoryRecords.withRecords(Compression.of(codecs.get(index)).build(),
                        new SimpleRecord("first-value".getBytes(StandardCharsets.UTF_8)),
                        new SimpleRecord("second-value".getBytes(StandardCharsets.UTF_8)));
                partitions.add(new ProduceRequestData.PartitionProduceData().setIndex(index).setRecords(records));
            }
            topics.add(new ProduceRequestData.TopicProduceData().setName(topic).setPartitionData(partitions));
        });
        ProduceRequestData data = new ProduceRequestData()
                .setAcks(requiredAcks)
                .setTimeoutMs(1000)
                .setTopicData(topics);
        ProduceRequest request = ProduceRequest.builder(data).build(version);
        ByteBuffer serialized = request.serializeWithHeader(new RequestHeader(ApiKeys.PRODUCE, version, "proxy-test", 1));
        byte[] frame = new byte[serialized.remaining()];
        serialized.get(frame);
        return frame;
    }
}
