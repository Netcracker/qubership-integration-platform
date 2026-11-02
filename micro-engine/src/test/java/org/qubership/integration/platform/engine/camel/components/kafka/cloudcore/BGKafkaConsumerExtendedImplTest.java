package org.qubership.integration.platform.engine.camel.components.kafka.cloudcore;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.AdminAdapter;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.BGKafkaConsumerConfig;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.VersionedGroupId;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.netcracker.cloud.framework.contexts.xversion.XVersionContextObject.X_VERSION_SERIALIZATION_NAME;
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
    private Consumer<String, String> replacementConsumer;
    @Mock
    private AdminAdapter adminAdapter;
    @Mock
    private Runnable closeCallback;
    @Mock
    private Metric metric;
    @Captor
    private ArgumentCaptor<java.util.function.Consumer<BlueGreenState>> stateListener;
    @Captor
    private ArgumentCaptor<ConsumerRebalanceListener> rebalanceListener;

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
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(TOPIC, 0, 10L, "key", "value");
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(consumerRecords(consumerRecord));

        consumer = newConsumer(activeState(), props -> {
            capturedProperties.set(new HashMap<>(props));
            return kafkaConsumer;
        });

        Optional<RecordsBatch<String, String>> result = consumer.poll(Duration.ZERO);

        assertTrue(result.isPresent());

        RecordsBatch<String, String> batch = result.get();
        assertEquals(1, batch.getBatch().size());
        assertSame(consumerRecord, batch.getBatch().get(0).getConsumerRecord());
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
        consumer = newConsumer(activeState(),
                Map.of(GROUP_ID_CONFIG, GROUP_ID, AUTO_OFFSET_RESET_CONFIG, "none"), props -> {
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
            return consumerRecords(record);
        });
        consumer = newConsumer(activeState(),
                Map.of(GROUP_ID_CONFIG, GROUP_ID, AUTO_OFFSET_RESET_CONFIG, "none"), props -> kafkaConsumer);

        RecordsBatch<String, String> batch = consumer.poll(Duration.ZERO).orElseThrow();
        Duration timeout = Duration.ofSeconds(1);
        consumer.commitSync(batch.getCommitMarker());
        consumer.commitSync(batch.getCommitMarker(), timeout);

        Map<TopicPartition, OffsetAndMetadata> position = Map.of(TOPIC_PARTITION, new OffsetAndMetadata(13L));
        assertSame(record, batch.getBatch().get(0).getConsumerRecord());
        assertEquals(position, batch.getCommitMarker().getPosition());
        verify(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        verify(kafkaConsumer).poll(Duration.ZERO);
        verify(kafkaConsumer).commitSync(position);
        verify(kafkaConsumer).commitSync(position, timeout);
        verifyNoMoreInteractions(kafkaConsumer);
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
        assertEquals(exception.getMessage(), assertThrows(IllegalStateException.class, consumer::resume).getMessage());
        assertEquals(exception.getMessage(), assertThrows(IllegalStateException.class, consumer::paused).getMessage());
        assertEquals(exception.getMessage(), assertThrows(IllegalStateException.class, consumer::assignment).getMessage());
    }

    @Test
    void shouldInitializeWhenStateIsDeliveredBySubscription() {
        BlueGreenState state = activeState();
        when(blueGreenStatePublisher.getBlueGreenState()).thenReturn(null);
        doAnswer(invocation -> {
            java.util.function.Consumer<BlueGreenState> listener = invocation.getArgument(0);
            listener.accept(state);
            return null;
        }).when(blueGreenStatePublisher).subscribe(any());
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(consumerRecords(
                new ConsumerRecord<>(TOPIC, 0, 4L, "key", "value")));
        BGKafkaConsumerConfig config = BGKafkaConsumerConfig
                .builder(consumerProperties(), Set.of(TOPIC), blueGreenStatePublisher)
                .consumerSupplier(props -> kafkaConsumer)
                .build();
        consumer = new BGKafkaConsumerExtendedImpl<>(config);

        RecordsBatch<String, String> batch = consumer.poll(Duration.ZERO).orElseThrow();

        assertEquals(state.getCurrent(), batch.getCommitMarker().getVersion());
        assertEquals(5L, batch.getCommitMarker().getPosition().get(TOPIC_PARTITION).offset());
    }

    @Test
    void shouldRetryInitializationWhenConsumerSupplierFails() {
        IllegalStateException failure = new IllegalStateException("Consumer unavailable");
        AtomicInteger attempts = new AtomicInteger();
        consumer = newConsumer(activeState(), props -> {
            if (attempts.getAndIncrement() == 0) {
                throw failure;
            }
            return kafkaConsumer;
        });
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(ConsumerRecords.empty());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> consumer.poll(Duration.ZERO)));
        assertTrue(consumer.poll(Duration.ZERO).isEmpty());
        assertTrue(consumer.poll(Duration.ZERO).isEmpty());

        assertEquals(2, attempts.get());
        verify(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        verify(kafkaConsumer, times(2)).poll(Duration.ZERO);
    }

    @Test
    void shouldReplaceConsumerWhenStateChangesEvenIfPreviousConsumerCannotClose() {
        BlueGreenState initialState = activeState();
        BlueGreenState nextState = new BlueGreenState(namespaceVersion(State.ACTIVE, 2), UPDATE_TIME.plusMinutes(1));
        AtomicInteger creations = new AtomicInteger();
        consumer = newConsumer(initialState, props ->
                creations.getAndIncrement() == 0 ? kafkaConsumer : replacementConsumer);
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(ConsumerRecords.empty());
        doThrow(new IllegalStateException("Consumer already disconnected")).when(kafkaConsumer).close();
        when(replacementConsumer.poll(Duration.ZERO)).thenReturn(consumerRecords(
                new ConsumerRecord<>(TOPIC, 0, 14L, "key", "value")));
        consumer.poll(Duration.ZERO);
        verify(blueGreenStatePublisher).subscribe(stateListener.capture());

        stateListener.getValue().accept(nextState);
        RecordsBatch<String, String> batch = consumer.poll(Duration.ZERO).orElseThrow();
        consumer.commitSync(batch.getCommitMarker());

        assertEquals(2, creations.get());
        assertEquals(nextState.getCurrent(), batch.getCommitMarker().getVersion());
        verify(kafkaConsumer).close();
        verify(replacementConsumer).subscribe(eq(Set.of(TOPIC)), any(ConsumerRebalanceListener.class));
        verify(replacementConsumer).commitSync(Map.of(TOPIC_PARTITION, new OffsetAndMetadata(15L)));
    }

    @Test
    void shouldFilterByLastVersionHeaderAndAdvanceCommitPositionsForSkippedRecords() {
        BlueGreenState state = candidateState();
        AtomicReference<Map<String, Object>> capturedProperties = new AtomicReference<>();
        ConsumerRecord<String, String> accepted = versionedRecord(10L, "v1", "v2");
        ConsumerRecord<String, String> skipped = versionedRecord(11L, "v1");
        ConsumerRecord<String, String> unversioned = new ConsumerRecord<>(TOPIC, 0, 12L, "key", "value");
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(new ConsumerRecords<>(
                Map.of(TOPIC_PARTITION, List.of(accepted, skipped, unversioned))));
        consumer = newConsumer(state, props -> {
            capturedProperties.set(new HashMap<>(props));
            return kafkaConsumer;
        });

        RecordsBatch<String, String> batch = consumer.poll(Duration.ZERO).orElseThrow();

        assertEquals(1, batch.getBatch().size());
        assertSame(accepted, batch.getBatch().getFirst().getConsumerRecord());
        assertEquals(Map.of(TOPIC_PARTITION, new OffsetAndMetadata(11L)),
                batch.getBatch().getFirst().getCommitMarker().getPosition());
        assertEquals(Map.of(TOPIC_PARTITION, new OffsetAndMetadata(13L)), batch.getCommitMarker().getPosition());
        assertEquals(state.getCurrent(), batch.getCommitMarker().getVersion());
        assertEquals(new VersionedGroupId(GROUP_ID, state.getCurrent().getVersion(), State.CANDIDATE,
                State.ACTIVE, UPDATE_TIME).toString(), capturedProperties.get().get(GROUP_ID_CONFIG));
        assertFalse(capturedProperties.get().containsKey(AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void shouldAcceptRecordsForAnotherVersionWhenFilterIsIgnored() {
        ConsumerRecord<String, String> record = versionedRecord(10L, "v1");
        when(blueGreenStatePublisher.getBlueGreenState()).thenReturn(candidateState());
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(consumerRecords(record));
        BGKafkaConsumerConfig config = BGKafkaConsumerConfig
                .builder(consumerProperties(), Set.of(TOPIC), blueGreenStatePublisher)
                .consumerSupplier(props -> kafkaConsumer)
                .ignoreFilter(true)
                .build();
        consumer = new BGKafkaConsumerExtendedImpl<>(config);

        RecordsBatch<String, String> batch = consumer.poll(Duration.ZERO).orElseThrow();

        assertEquals(1, batch.getBatch().size());
        assertSame(record, batch.getBatch().getFirst().getConsumerRecord());
    }

    @Test
    void shouldLeaveOffsetResetAbsentWhenItIsNotConfigured() {
        AtomicReference<Map<String, Object>> capturedProperties = new AtomicReference<>();
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(ConsumerRecords.empty());
        consumer = newConsumer(activeState(), Map.of(GROUP_ID_CONFIG, GROUP_ID), props -> {
            capturedProperties.set(new HashMap<>(props));
            return kafkaConsumer;
        });

        assertTrue(consumer.poll(Duration.ZERO).isEmpty());

        assertFalse(capturedProperties.get().containsKey(AUTO_OFFSET_RESET_CONFIG));
        assertEquals(GROUP_ID, capturedProperties.get().get(GROUP_ID_CONFIG));
    }

    @Test
    void shouldSeekAlignedOffsetsOnlyForAssignedPartitions() {
        TopicPartition otherPartition = new TopicPartition(TOPIC, 1);
        Map<TopicPartition, OffsetAndMetadata> offsets = Map.of(
                TOPIC_PARTITION, new OffsetAndMetadata(7L), otherPartition, new OffsetAndMetadata(19L));
        when(blueGreenStatePublisher.getBlueGreenState()).thenReturn(activeState());
        when(adminAdapter.listConsumerGroup()).thenReturn(List.of(new ConsumerGroupListing(GROUP_ID, false)));
        when(adminAdapter.listConsumerGroupOffsets(GROUP_ID)).thenReturn(offsets);
        when(kafkaConsumer.partitionsFor(TOPIC)).thenReturn(List.of(
                new PartitionInfo(TOPIC, 0, null, null, null), new PartitionInfo(TOPIC, 1, null, null, null)));
        when(kafkaConsumer.committed(Set.of(TOPIC_PARTITION, otherPartition))).thenReturn(offsets);
        when(kafkaConsumer.poll(Duration.ZERO)).thenReturn(ConsumerRecords.empty());
        BGKafkaConsumerConfig config = BGKafkaConsumerConfig
                .builder(consumerProperties(), Set.of(TOPIC), blueGreenStatePublisher)
                .consumerSupplier(props -> kafkaConsumer)
                .adminAdapterSupplier(props -> adminAdapter)
                .build();
        consumer = new BGKafkaConsumerExtendedImpl<>(config);
        consumer.poll(Duration.ZERO);
        verify(kafkaConsumer).subscribe(eq(Set.of(TOPIC)), rebalanceListener.capture());

        rebalanceListener.getValue().onPartitionsRevoked(Set.of(TOPIC_PARTITION));
        rebalanceListener.getValue().onPartitionsAssigned(Set.of(TOPIC_PARTITION));

        verify(kafkaConsumer).seek(TOPIC_PARTITION, offsets.get(TOPIC_PARTITION));
        verify(kafkaConsumer, never()).seek(eq(otherPartition), any(OffsetAndMetadata.class));
        verify(adminAdapter).close();
    }

    @Test
    void shouldUnsubscribeAndRunCallbackWhenClosedBeforeFirstPoll() {
        consumer = newConsumer(activeState());
        consumer.setOnCloseCallback(closeCallback);
        verify(blueGreenStatePublisher).subscribe(stateListener.capture());

        consumer.close();

        verify(blueGreenStatePublisher).unsubscribe(stateListener.getValue());
        verify(closeCallback).run();
        verifyNoInteractions(kafkaConsumer);
    }

    private BGKafkaConsumerExtendedImpl<String, String> newConsumer(BlueGreenState state) {
        return newConsumer(state, props -> kafkaConsumer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BGKafkaConsumerExtendedImpl<String, String> newConsumer(
            BlueGreenState state, Function<Map<String, Object>, Consumer> consumerSupplier) {
        return newConsumer(state, consumerProperties(), consumerSupplier);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BGKafkaConsumerExtendedImpl<String, String> newConsumer(
            BlueGreenState state, Map<String, Object> properties,
            Function<Map<String, Object>, Consumer> consumerSupplier) {
        when(blueGreenStatePublisher.getBlueGreenState()).thenReturn(state);

        BGKafkaConsumerConfig config = BGKafkaConsumerConfig
                .builder(properties, Set.of(TOPIC), blueGreenStatePublisher)
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

    private static ConsumerRecords<String, String> consumerRecords(ConsumerRecord<String, String> record) {
        return new ConsumerRecords<>(Map.of(TOPIC_PARTITION, List.of(record)));
    }

    private static ConsumerRecord<String, String> versionedRecord(long offset, String... versions) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, offset, "key", "value");
        for (String version : versions) {
            record.headers().add(X_VERSION_SERIALIZATION_NAME, version.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private static BlueGreenState candidateState() {
        return new BlueGreenState(namespaceVersion(State.CANDIDATE, 2),
                namespaceVersion(State.ACTIVE, 1), UPDATE_TIME);
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
