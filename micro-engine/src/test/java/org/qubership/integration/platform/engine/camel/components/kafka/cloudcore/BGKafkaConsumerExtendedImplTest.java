package org.qubership.integration.platform.engine.camel.components.kafka.cloudcore;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.BGKafkaConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.namespaceVersion;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class BGKafkaConsumerExtendedImplTest {

    private static final String TOPIC = "topic-a";
    private static final String GROUP_ID = "group-a";
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, 0);
    private static final OffsetDateTime UPDATE_TIME = OffsetDateTime.parse("2026-06-20T09:00:00Z");

    private BGKafkaConsumerExtendedImpl<String, String> consumer;

    @Mock
    private BlueGreenStatePublisher blueGreenStatePublisher;
    @Mock
    private Consumer<String, String> kafkaConsumer;
    @Mock
    private Runnable closeCallback;
    @Mock
    private Metric metric;

    @Test
    void shouldSubscribeAndReturnEmptyMetricsBeforeKafkaConsumerInitialized() {
        consumer = newConsumer(activeState());

        assertTrue(consumer.metrics().isEmpty());

        verify(blueGreenStatePublisher).subscribe(any());
        verifyNoInteractions(kafkaConsumer);
    }

    @Test
    void shouldReturnEmptyPollAndSkipKafkaConsumerCreationWhenStateIdle() {
        AtomicBoolean supplierCalled = new AtomicBoolean();
        consumer = newConsumer(idleState(), props -> {
            supplierCalled.set(true);
            return kafkaConsumer;
        });

        Optional<RecordsBatch<String, String>> result = consumer.poll(Duration.ZERO);

        assertTrue(result.isEmpty());
        assertFalse(supplierCalled.get());
    }

    @Test
    void shouldInitializeKafkaConsumerWithPlainGroupIdAndReturnPolledRecords() {
        AtomicReference<Map<String, Object>> capturedProperties = new AtomicReference<>();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 10L, "key", "value");
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(records(record));

        consumer = newConsumer(activeState(), props -> {
            capturedProperties.set(new HashMap<>(props));
            return kafkaConsumer;
        });

        Optional<RecordsBatch<String, String>> result = consumer.poll(Duration.ZERO);

        assertTrue(result.isPresent());

        RecordsBatch<String, String> batch = result.get();
        assertEquals(1, batch.getBatch().size());
        assertSame(record, batch.getBatch().get(0).getConsumerRecord());
        assertEquals(activeState().getCurrent(), batch.getCommitMarker().getVersion());
        assertEquals(11L, batch.getCommitMarker().getPosition().get(TOPIC_PARTITION).offset());
        assertEquals(batch.getCommitMarker(), batch.getBatch().get(0).getCommitMarker());

        assertEquals(GROUP_ID, capturedProperties.get().get(GROUP_ID_CONFIG));
        assertEquals("kept", capturedProperties.get().get("custom.option"));
        assertFalse(capturedProperties.get().containsKey(AUTO_OFFSET_RESET_CONFIG));

        verify(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        verify(kafkaConsumer).poll(Duration.ZERO);
    }

    @Test
    void shouldCommitOnlyMarkersForActiveState() {
        BlueGreenState activeState = activeState();
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(ConsumerRecords.empty());
        consumer = newConsumer(activeState);
        consumer.poll(Duration.ZERO);

        Map<TopicPartition, OffsetAndMetadata> activePosition = Map.of(
                TOPIC_PARTITION, new OffsetAndMetadata(12L));
        Map<TopicPartition, OffsetAndMetadata> inactivePosition = Map.of(
                TOPIC_PARTITION, new OffsetAndMetadata(25L));
        CommitMarker activeMarker = new CommitMarker(activeState.getCurrent(), activePosition);
        CommitMarker inactiveMarker = new CommitMarker(inactiveVersion(), inactivePosition);
        Duration timeout = Duration.ofSeconds(1);

        consumer.commitSync(activeMarker);
        consumer.commitSync(activeMarker, timeout);
        consumer.commitSync(inactiveMarker);
        consumer.commitSync(inactiveMarker, timeout);

        verify(kafkaConsumer).commitSync(activePosition);
        verify(kafkaConsumer).commitSync(activePosition, timeout);
        verify(kafkaConsumer, never()).commitSync(inactivePosition);
        verify(kafkaConsumer, never()).commitSync(inactivePosition, timeout);
    }

    @Test
    void shouldDelegateMetricsAfterKafkaConsumerInitialized() {
        MetricName metricName = new MetricName("records-consumed-total", "consumer", "Records consumed", Map.of());
        Map<MetricName, Metric> metrics = Map.of(metricName, metric);
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(ConsumerRecords.empty());
        doReturn(metrics).when(kafkaConsumer).metrics();
        consumer = newConsumer(activeState());
        consumer.poll(Duration.ZERO);

        assertSame(metrics, consumer.metrics());
    }

    @Test
    void shouldDelegateLifecycleMethodsAfterKafkaConsumerInitialized() {
        Set<TopicPartition> assignment = Set.of(TOPIC_PARTITION);
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(ConsumerRecords.empty());
        when(kafkaConsumer.assignment()).thenReturn(assignment);
        when(kafkaConsumer.paused()).thenReturn(assignment);
        consumer = newConsumer(activeState());
        consumer.poll(Duration.ZERO);
        consumer.setOnCloseCallback(closeCallback);

        consumer.pause();
        consumer.resume();

        assertSame(assignment, consumer.assignment());
        assertSame(assignment, consumer.paused());

        consumer.wakeup();
        consumer.close();

        verify(kafkaConsumer).pause(assignment);
        verify(kafkaConsumer).resume(assignment);
        verify(kafkaConsumer).wakeup();
        verify(kafkaConsumer).close();
        verify(closeCallback).run();
        verify(blueGreenStatePublisher).unsubscribe(any());
    }

    @Test
    void shouldThrowWhenPauseCalledBeforeKafkaConsumerInitialized() {
        consumer = newConsumer(activeState());

        IllegalStateException exception = assertThrows(IllegalStateException.class, consumer::pause);

        assertEquals("Consumer not initiated yet. Initiate with poll()", exception.getMessage());
    }

    private BGKafkaConsumerExtendedImpl<String, String> newConsumer(BlueGreenState state) {
        return newConsumer(state, props -> kafkaConsumer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BGKafkaConsumerExtendedImpl<String, String> newConsumer(
            BlueGreenState state, Function<Map<String, Object>, Consumer> consumerSupplier) {
        when(blueGreenStatePublisher.getBlueGreenState()).thenReturn(state);

        BGKafkaConsumerConfig config = BGKafkaConsumerConfig
                .builder(consumerProperties(), Set.of(TOPIC), blueGreenStatePublisher)
                .consumerSupplier(consumerSupplier)
                .build();

        return new BGKafkaConsumerExtendedImpl<>(config);
    }

    private static Map<String, Object> consumerProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(GROUP_ID_CONFIG, GROUP_ID);
        properties.put(AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put("custom.option", "kept");
        return properties;
    }

    private static ConsumerRecords<String, String> records(ConsumerRecord<String, String> record) {
        return new ConsumerRecords<>(Map.of(TOPIC_PARTITION, List.of(record)));
    }

    private static BlueGreenState activeState() {
        return new BlueGreenState(namespaceVersion(State.ACTIVE, 1), UPDATE_TIME);
    }

    private static BlueGreenState idleState() {
        return new BlueGreenState(namespaceVersion(State.IDLE, 1), UPDATE_TIME);
    }

    private static NamespaceVersion inactiveVersion() {
        return namespaceVersion(State.CANDIDATE, 2);
    }
}
