package org.qubership.integration.platform.engine.camel.components.kafka.cloudcore;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.model.Version;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.BGKafkaConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BGKafkaConsumerExtendedImplTest {
    private static final String TOPIC = "topic-a";
    private static final String GROUP_ID = "group-a";
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, 0);

    private BGKafkaConsumerExtendedImpl<String, String> consumer;

    @Mock
    private BlueGreenStatePublisher blueGreenStatePublisher;
    @Mock
    private Consumer<String, String> kafkaConsumer;

    @Test
    void shouldPreserveNativeNoneAndPropagateMissingOffsetsWithoutAligningPlainGroup() {
        AtomicReference<Map<String, Object>> properties = new AtomicReference<>();
        AtomicReference<ConsumerRebalanceListener> listener = new AtomicReference<>();
        NoOffsetForPartitionException failure = new NoOffsetForPartitionException(Set.of(TOPIC_PARTITION));
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(1));
            return null;
        }).when(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        when(kafkaConsumer.poll(Duration.ZERO)).thenAnswer(invocation -> {
            listener.get().onPartitionsAssigned(Set.of(TOPIC_PARTITION));
            throw failure;
        });
        consumer = newConsumer(props -> {
            properties.set(new HashMap<>(props));
            return kafkaConsumer;
        });

        assertSame(failure, assertThrows(NoOffsetForPartitionException.class, () -> consumer.poll(Duration.ZERO)));

        assertEquals("none", properties.get().get(AUTO_OFFSET_RESET_CONFIG));
        assertEquals(GROUP_ID, properties.get().get(GROUP_ID_CONFIG));
        verify(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        verify(kafkaConsumer).poll(Duration.ZERO);
        verifyNoMoreInteractions(kafkaConsumer);
    }

    @Test
    void shouldCommitRecordsWhenPlainGroupUsesNativeNone() {
        AtomicReference<ConsumerRebalanceListener> listener = new AtomicReference<>();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 12L, "key", "value");
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(1));
            return null;
        }).when(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        when(kafkaConsumer.poll(Duration.ZERO)).thenAnswer(invocation -> {
            listener.get().onPartitionsAssigned(Set.of(TOPIC_PARTITION));
            return new ConsumerRecords<>(Map.of(TOPIC_PARTITION, List.of(record)));
        });
        consumer = newConsumer(props -> kafkaConsumer);

        RecordsBatch<String, String> batch = consumer.poll(Duration.ZERO).orElseThrow();
        Duration timeout = Duration.ofSeconds(1);
        consumer.commitSync(batch.getCommitMarker());
        consumer.commitSync(batch.getCommitMarker(), timeout);

        Map<TopicPartition, OffsetAndMetadata> position = Map.of(TOPIC_PARTITION, new OffsetAndMetadata(13L));
        assertSame(record, batch.getBatch().getFirst().getConsumerRecord());
        assertEquals(position, batch.getCommitMarker().getPosition());
        verify(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        verify(kafkaConsumer).poll(Duration.ZERO);
        verify(kafkaConsumer).commitSync(position);
        verify(kafkaConsumer).commitSync(position, timeout);
        verifyNoMoreInteractions(kafkaConsumer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BGKafkaConsumerExtendedImpl<String, String> newConsumer(Function<Map<String, Object>, Consumer> supplier) {
        when(blueGreenStatePublisher.getBlueGreenState()).thenReturn(new BlueGreenState(
                new NamespaceVersion("snapshot", State.ACTIVE, new Version(1)), OffsetDateTime.parse("2026-06-20T09:00:00Z")));
        BGKafkaConsumerConfig config = BGKafkaConsumerConfig
                .builder(Map.of(GROUP_ID_CONFIG, GROUP_ID, AUTO_OFFSET_RESET_CONFIG, "none"), Set.of(TOPIC), blueGreenStatePublisher)
                .consumerSupplier(supplier)
                .build();
        return new BGKafkaConsumerExtendedImpl<>(config);
    }
}
