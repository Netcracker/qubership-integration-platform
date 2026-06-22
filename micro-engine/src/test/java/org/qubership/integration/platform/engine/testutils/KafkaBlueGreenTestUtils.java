package org.qubership.integration.platform.engine.testutils;

import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.model.Version;
import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.Record;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;

import java.util.Map;
import java.util.Optional;

public final class KafkaBlueGreenTestUtils {

    public static final String NAMESPACE = "namespace-a";

    private KafkaBlueGreenTestUtils() {
    }

    public static NamespaceVersion namespaceVersion(State state, int version) {
        return new NamespaceVersion(NAMESPACE, state, new Version(version));
    }

    public static CommitMarker commitMarker(TopicPartition topicPartition, long offset) {
        return new CommitMarker(
                namespaceVersion(State.ACTIVE, 1),
                Map.of(topicPartition, new OffsetAndMetadata(offset))
        );
    }

    public static Record<Object, Object> record(
            String topic,
            int partition,
            long offset,
            Object key,
            Object value,
            CommitMarker marker) {
        return new Record<>(consumerRecord(topic, partition, offset, key, value, new RecordHeaders()), marker);
    }

    public static ConsumerRecord<Object, Object> consumerRecord(
            String topic,
            int partition,
            long offset,
            Object key,
            Object value,
            Headers headers) {
        return new ConsumerRecord<>(
                topic,
                partition,
                offset,
                1234L,
                TimestampType.CREATE_TIME,
                1,
                1,
                key,
                value,
                headers,
                Optional.empty()
        );
    }
}
