package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.PollOnError;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.support.BridgeExceptionHandlerToErrorHandler;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.commitMarker;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.record;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaBGFetchRecordsTest {

    private static final String TOPIC = "topic-a";
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, 0);

    private KafkaCustomConfiguration configuration;
    private Properties kafkaProps;

    @Mock
    private KafkaBGConsumer kafkaConsumer;
    @Mock
    private KafkaCustomEndpoint endpoint;
    @Mock
    private KafkaBGClientFactory kafkaClientFactory;
    @Mock
    private Processor processor;
    @Mock
    private ExceptionHandler exceptionHandler;
    @Mock
    private BridgeExceptionHandlerToErrorHandler bridge;
    @Mock
    private BGKafkaConsumerExtended<Object, Object> consumer;

    @BeforeEach
    void setUp() {
        configuration = new KafkaCustomConfiguration();
        configuration.setPollTimeoutMs(25L);
        configuration.setCommitTimeoutMs(50L);
        configuration.setShutdownTimeout(0);
        kafkaProps = new Properties();
    }

    @Test
    void shouldSkipRunWhenKafkaConsumerIsNotRunnable() {
        TestableKafkaBGFetchRecords fetchRecords = testableFetchRecords();
        when(kafkaConsumer.isRunAllowed()).thenReturn(false);

        fetchRecords.run();

        assertEquals(0, fetchRecords.createConsumerCalls);
        assertEquals(0, fetchRecords.startPollingCalls);
    }

    @Test
    void shouldCreateConsumerAndStartPollingWhenRunAllowed() {
        AtomicBoolean runAllowed = new AtomicBoolean(true);
        TestableKafkaBGFetchRecords fetchRecords = testableFetchRecords();
        fetchRecords.onStartPolling = () -> runAllowed.set(false);
        stubRunnable(runAllowed);

        fetchRecords.run();

        assertEquals(1, fetchRecords.createConsumerCalls);
        assertEquals(1, fetchRecords.startPollingCalls);
        assertTrue(fetchRecords.isConnected());
    }

    @Test
    void shouldCreateConsumerWithSplitTopicNames() {
        KafkaBGFetchRecords fetchRecords = fetchRecords("topic-a,topic-b", PollOnError.ERROR_HANDLER);
        stubEndpoint();
        when(endpoint.getKafkaClientFactory()).thenReturn(kafkaClientFactory);
        when(kafkaClientFactory.getConsumer(
                same(kafkaProps),
                eq(ConsumerConsistencyMode.EVENTUAL),
                eq(List.of("topic-a", "topic-b"))
        )).thenReturn(consumer);

        fetchRecords.createConsumer();

        verify(kafkaClientFactory).getConsumer(
                same(kafkaProps),
                eq(ConsumerConsistencyMode.EVENTUAL),
                eq(List.of("topic-a", "topic-b"))
        );
    }

    @Test
    void shouldProcessPolledRecordsAndReleaseExchangeWhenPollReturnsBatch() throws Exception {
        AtomicBoolean runAllowed = new AtomicBoolean(true);
        KafkaBGFetchRecords fetchRecords = fetchRecords(TOPIC, PollOnError.ERROR_HANDLER);
        CommitMarker marker = commitMarker(TOPIC_PARTITION, 8L);
        Exchange exchange = MockExchanges.defaultExchange();
        stubRunnable(runAllowed);
        stubEndpoint();
        stubConsumerFactory(TOPIC);
        when(endpoint.getConfiguration()).thenReturn(configuration);
        when(kafkaConsumer.createExchange(false)).thenReturn(exchange);
        when(kafkaConsumer.getProcessor()).thenReturn(processor);
        when(consumer.poll(Duration.ofMillis(25L))).thenAnswer(invocation -> {
            runAllowed.set(false);
            return Optional.of(new RecordsBatch<>(
                    List.of(record(TOPIC, 0, 7L, "key-a", "value-a", marker)),
                    marker
            ));
        });

        fetchRecords.createConsumer();
        fetchRecords.setConnected(true);
        fetchRecords.startPolling();

        verify(consumer).poll(Duration.ofMillis(25L));
        verify(processor).process(exchange);
        verify(kafkaConsumer).releaseExchange(exchange, false);
        verify(consumer, never()).commitSync(any(CommitMarker.class));
        verify(consumer, never()).commitSync(any(CommitMarker.class), any(Duration.class));
    }

    @Test
    void shouldSendPollingExceptionToBridgeWhenErrorHandlerStrategyConfigured() {
        RuntimeException failure = new RuntimeException("poll failed");
        KafkaBGFetchRecords fetchRecords = fetchRecords(TOPIC, PollOnError.ERROR_HANDLER);
        stubRunnable(new AtomicBoolean(true));
        stubEndpoint();
        stubConsumerFactory(TOPIC);
        when(endpoint.getConfiguration()).thenReturn(configuration);
        when(consumer.poll(Duration.ofMillis(25L))).thenThrow(failure);

        fetchRecords.createConsumer();
        fetchRecords.setConnected(true);
        fetchRecords.startPolling();

        verify(bridge).handleException(failure);
        verify(consumer, never()).close();
    }

    @Test
    void shouldCloseConsumerWhenReconnectStrategyHandlesPollingException() {
        RuntimeException failure = new RuntimeException("poll failed");
        KafkaBGFetchRecords fetchRecords = fetchRecords(TOPIC, PollOnError.RECONNECT);
        stubRunnable(new AtomicBoolean(true));
        stubEndpoint();
        stubConsumerFactory(TOPIC);
        when(endpoint.getConfiguration()).thenReturn(configuration);
        when(consumer.poll(Duration.ofMillis(25L))).thenThrow(failure);

        fetchRecords.createConsumer();
        fetchRecords.setConnected(true);
        fetchRecords.startPolling();

        assertFalse(fetchRecords.isConnected());
        verify(consumer).close();
        verifyNoInteractions(bridge);
    }

    @Test
    void shouldWakeUpConsumerWhenStopped() {
        KafkaBGFetchRecords fetchRecords = fetchRecords(TOPIC, PollOnError.ERROR_HANDLER);
        stubEndpoint();
        stubConsumerFactory(TOPIC);
        when(endpoint.getConfiguration()).thenReturn(configuration);

        fetchRecords.createConsumer();
        fetchRecords.stop();

        verify(consumer).wakeup();
    }

    private KafkaBGFetchRecords fetchRecords(String topicName, PollOnError pollOnError) {
        return new KafkaBGFetchRecords(
                kafkaConsumer,
                bridge,
                topicName,
                Pattern.compile(topicName),
                "0",
                kafkaProps,
                ConsumerConsistencyMode.EVENTUAL,
                pollOnError
        );
    }

    private TestableKafkaBGFetchRecords testableFetchRecords() {
        return new TestableKafkaBGFetchRecords(
                kafkaConsumer,
                bridge,
                TOPIC,
                Pattern.compile(TOPIC),
                "0",
                kafkaProps,
                ConsumerConsistencyMode.EVENTUAL,
                PollOnError.ERROR_HANDLER
        );
    }

    private void stubRunnable(AtomicBoolean runAllowed) {
        when(kafkaConsumer.isRunAllowed()).thenAnswer(invocation -> runAllowed.get());
        when(kafkaConsumer.isStoppingOrStopped()).thenReturn(false);
        when(kafkaConsumer.isSuspendingOrSuspended()).thenReturn(false);
    }

    private void stubEndpoint() {
        when(kafkaConsumer.getEndpoint()).thenReturn(endpoint);
    }

    private void stubConsumerFactory(String topicName) {
        when(endpoint.getKafkaClientFactory()).thenReturn(kafkaClientFactory);
        when(kafkaClientFactory.getConsumer(
                same(kafkaProps),
                eq(ConsumerConsistencyMode.EVENTUAL),
                eq(List.of(topicName.split(",")))
        )).thenReturn(consumer);
    }

    private static final class TestableKafkaBGFetchRecords extends KafkaBGFetchRecords {

        private int createConsumerCalls;
        private int startPollingCalls;
        private Runnable onStartPolling = () -> {};

        private TestableKafkaBGFetchRecords(
                KafkaBGConsumer kafkaConsumer,
                BridgeExceptionHandlerToErrorHandler bridge,
                String topicName,
                Pattern topicPattern,
                String id,
                Properties kafkaProps,
                ConsumerConsistencyMode consistencyMode,
                PollOnError pollOnError) {
            super(kafkaConsumer, bridge, topicName, topicPattern, id, kafkaProps, consistencyMode, pollOnError);
        }

        @Override
        protected void createConsumer() {
            createConsumerCalls++;
        }

        @Override
        protected void startPolling() {
            startPollingCalls++;
            onStartPolling.run();
        }
    }
}
