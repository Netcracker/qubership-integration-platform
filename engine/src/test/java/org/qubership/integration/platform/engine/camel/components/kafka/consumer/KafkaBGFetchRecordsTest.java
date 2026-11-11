package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.model.Version;
import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import com.netcracker.cloud.maas.bluegreen.kafka.Record;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.PollOnError;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.BridgeExceptionHandlerToErrorHandler;
import org.apache.camel.support.DefaultExchange;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaBGFetchRecordsTest {

    private static final String TOPIC = "topic-a";
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(25);
    private static final Duration COMMIT_TIMEOUT = Duration.ofMillis(50);

    @Mock
    private KafkaBGConsumer kafkaConsumer;
    @Mock
    private KafkaCustomEndpoint endpoint;
    @Mock
    private KafkaBGClientFactory kafkaClientFactory;
    @Mock
    private Processor processor;
    @Mock
    private BridgeExceptionHandlerToErrorHandler bridge;
    @Mock
    private BGKafkaConsumerExtended<Object, Object> consumer;

    private final AtomicBoolean runAllowed = new AtomicBoolean(true);
    private KafkaBGFetchRecords fetchRecords;

    @BeforeEach
    void setUp() {
        KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();
        configuration.setPollTimeoutMs(POLL_TIMEOUT.toMillis());
        configuration.setCommitTimeoutMs(COMMIT_TIMEOUT.toMillis());
        Properties kafkaProps = new Properties();
        fetchRecords = new KafkaBGFetchRecords(kafkaConsumer, bridge, TOPIC, Pattern.compile(TOPIC),
                "0", kafkaProps, ConsumerConsistencyMode.EVENTUAL, PollOnError.ERROR_HANDLER);
        when(kafkaConsumer.isRunAllowed()).thenAnswer(invocation -> runAllowed.get());
        when(kafkaConsumer.getEndpoint()).thenReturn(endpoint);
        when(kafkaConsumer.getProcessor()).thenReturn(processor);
        when(endpoint.getConfiguration()).thenReturn(configuration);
        when(endpoint.getKafkaClientFactory()).thenReturn(kafkaClientFactory);
        when(kafkaClientFactory.getConsumer(same(kafkaProps), eq(ConsumerConsistencyMode.EVENTUAL),
                eq(List.of(TOPIC)))).thenReturn(consumer);
    }

    @Test
    void shouldResumeAfterAuthenticationFailureWithoutCommittingThePreviousRecord() throws Exception {
        CommitMarker firstMarker = commitMarker(8L);
        CommitMarker recoveredMarker = commitMarker(9L);
        Exchange firstExchange = new DefaultExchange(new DefaultCamelContext());
        Exchange recoveredExchange = new DefaultExchange(new DefaultCamelContext());
        SslAuthenticationException failure = new SslAuthenticationException("TLS certificate was rejected");
        when(kafkaConsumer.createExchange(false)).thenReturn(firstExchange, recoveredExchange);
        when(consumer.poll(POLL_TIMEOUT))
                .thenReturn(Optional.of(batch(7L, "before-failure", firstMarker)))
                .thenThrow(failure)
                .thenAnswer(invocation -> {
                    runAllowed.set(false);
                    return Optional.of(batch(8L, "after-recovery", recoveredMarker));
                });

        fetchRecords.run();

        verify(consumer, times(3)).poll(POLL_TIMEOUT);
        verify(processor).process(firstExchange);
        verify(processor).process(recoveredExchange);
        assertEquals("before-failure", firstExchange.getMessage().getBody());
        assertEquals("after-recovery", recoveredExchange.getMessage().getBody());
        verify(kafkaConsumer).releaseExchange(firstExchange, false);
        verify(kafkaConsumer).releaseExchange(recoveredExchange, false);
        verify(bridge).handleException(failure);
        verify(consumer, never()).commitSync(any(CommitMarker.class));
        verify(consumer, never()).commitSync(any(CommitMarker.class), any(Duration.class));
        verify(consumer).close();
    }

    @Test
    void shouldCommitTheCurrentRecordWhenExchangeCreationFails() {
        CommitMarker marker = commitMarker(8L);
        IllegalStateException failure = new IllegalStateException("Exchange creation failed");
        when(kafkaConsumer.createExchange(false)).thenThrow(failure);
        when(consumer.poll(POLL_TIMEOUT)).thenAnswer(invocation -> {
            runAllowed.set(false);
            return Optional.of(batch(7L, "failed-record", marker));
        });

        fetchRecords.run();

        verify(bridge).handleException(failure);
        verify(consumer).commitSync(marker, COMMIT_TIMEOUT);
        verify(consumer, never()).commitSync(any(CommitMarker.class));
        verify(consumer).close();
    }

    private static CommitMarker commitMarker(long offset) {
        return new CommitMarker(new NamespaceVersion("snapshot", State.ACTIVE, new Version(1)),
                Map.of(TOPIC_PARTITION, new OffsetAndMetadata(offset)));
    }

    private static RecordsBatch<Object, Object> batch(long offset, String value, CommitMarker marker) {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(TOPIC, 0, offset, "key", value);
        return new RecordsBatch<>(List.of(new Record<>(record, marker)), marker);
    }
}
