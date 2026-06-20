package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.Record;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.serde.KafkaHeaderDeserializer;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.commitMarker;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.consumerRecord;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.record;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaBGRecordProcessorTest {

    private static final String TOPIC = "topic-a";
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, 2);

    private final KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();

    @Mock
    private Processor processor;
    @Mock
    private BGKafkaConsumerExtended<Object, Object> consumer;
    @Mock
    private ExceptionHandler exceptionHandler;
    @Mock
    private HeaderFilterStrategy headerFilterStrategy;
    @Mock
    private KafkaHeaderDeserializer headerDeserializer;

    @Test
    void shouldPopulateExchangeAndPropagateHeadersWhenProcessingSucceeds() throws Exception {
        Headers headers = new RecordHeaders()
                .add("allowed", bytes("raw-value"))
                .add("blocked", bytes("blocked-value"));
        ConsumerRecord<Object, Object> consumerRecord = consumerRecord(TOPIC, 2, 7L, "key-a", "value-a", headers);
        CommitMarker marker = commitMarker(TOPIC_PARTITION, 8L);
        Record<Object, Object> record = new Record<>(consumerRecord, marker);
        Exchange exchange = MockExchanges.defaultExchange();
        configuration.setHeaderFilterStrategy(headerFilterStrategy);
        configuration.setHeaderDeserializer(headerDeserializer);
        when(headerFilterStrategy.applyFilterToExternalHeaders(eq("allowed"), any(), same(exchange)))
                .thenReturn(false);
        when(headerFilterStrategy.applyFilterToExternalHeaders(eq("blocked"), any(), same(exchange)))
                .thenReturn(true);
        when(headerDeserializer.deserialize("allowed", bytes("raw-value"))).thenReturn("decoded-value");

        KafkaBGRecordProcessor.ProcessResult result = recordProcessor()
                .processExchange(exchange, record, KafkaBGRecordProcessor.ProcessResult.newUnprocessed(), exceptionHandler);

        assertFalse(result.isBreakOnErrorHit());
        assertSame(marker, result.getPartitionLastOffset());
        assertSame("value-a", exchange.getMessage().getBody());
        assertEquals(2, exchange.getMessage().getHeader(KafkaConstants.PARTITION));
        assertEquals(TOPIC, exchange.getMessage().getHeader(KafkaConstants.TOPIC));
        assertEquals(7L, exchange.getMessage().getHeader(KafkaConstants.OFFSET));
        assertSame(headers, exchange.getMessage().getHeader(KafkaConstants.HEADERS));
        assertEquals(1234L, exchange.getMessage().getHeader(KafkaConstants.TIMESTAMP));
        assertEquals(1234L, exchange.getMessage().getHeader(Exchange.MESSAGE_TIMESTAMP));
        assertEquals("key-a", exchange.getMessage().getHeader(KafkaConstants.KEY));
        assertEquals("decoded-value", exchange.getMessage().getHeader("allowed"));
        assertNull(exchange.getMessage().getHeader("blocked"));
        verify(processor).process(exchange);
    }

    @Test
    void shouldHandleExceptionAndContinueWhenBreakOnFirstErrorDisabled() throws Exception {
        RuntimeException failure = new RuntimeException("processing failed");
        doThrow(failure).when(processor).process(any(Exchange.class));
        Exchange exchange = MockExchanges.defaultExchange();

        KafkaBGRecordProcessor.ProcessResult result = recordProcessor()
                .processExchange(exchange, record(TOPIC, 2, 7L, "key-a", "value-a",
                                commitMarker(TOPIC_PARTITION, 8L)),
                        KafkaBGRecordProcessor.ProcessResult.newUnprocessed(), exceptionHandler);

        assertFalse(result.isBreakOnErrorHit());
        assertNull(result.getPartitionLastOffset());
        assertSame(failure, exchange.getException());
        verify(exceptionHandler).handleException("Error during processing", exchange, failure);
        verifyNoInteractions(consumer);
    }

    @Test
    void shouldForceCommitLastOffsetAndBreakWhenBreakOnFirstErrorEnabled() throws Exception {
        configuration.setBreakOnFirstError(true);
        configuration.setCommitTimeoutMs(321L);
        CommitMarker lastMarker = commitMarker(TOPIC_PARTITION, 8L);
        KafkaBGRecordProcessor.ProcessResult lastResult = successfulResult(lastMarker);
        RuntimeException failure = new RuntimeException("processing failed");
        doThrow(failure).when(processor).process(any(Exchange.class));
        Exchange exchange = MockExchanges.defaultExchange();

        KafkaBGRecordProcessor.ProcessResult result = recordProcessor()
                .processExchange(exchange, record(TOPIC, 2, 7L, "key-a", "value-a",
                        commitMarker(TOPIC_PARTITION, 9L)), lastResult, exceptionHandler);

        assertTrue(result.isBreakOnErrorHit());
        assertSame(lastMarker, result.getPartitionLastOffset());
        assertSame(failure, exchange.getException());
        verify(consumer).commitSync(lastMarker, Duration.ofMillis(321L));
        verifyNoInteractions(exceptionHandler);
    }

    @Test
    void shouldCommitOffsetOnlyWhenStoppingOrForceCommitEnabled() {
        configuration.setCommitTimeoutMs(250L);
        CommitMarker marker = commitMarker(TOPIC_PARTITION, 8L);

        KafkaBGRecordProcessor.commitOffset(configuration, consumer, null, true, true);
        KafkaBGRecordProcessor.commitOffset(configuration, consumer, marker, false, false);
        KafkaBGRecordProcessor.commitOffset(configuration, consumer, marker, true, false);
        KafkaBGRecordProcessor.commitOffset(configuration, consumer, marker, false, true);

        verify(consumer, times(2)).commitSync(marker, Duration.ofMillis(250L));
    }

    private KafkaBGRecordProcessor recordProcessor() {
        return new KafkaBGRecordProcessor(configuration, processor, consumer);
    }

    private KafkaBGRecordProcessor.ProcessResult successfulResult(CommitMarker marker) {
        Processor successfulProcessor = exchange -> {};
        KafkaBGRecordProcessor successfulRecordProcessor =
                new KafkaBGRecordProcessor(configuration, successfulProcessor, consumer);

        return successfulRecordProcessor.processExchange(
                MockExchanges.defaultExchange(),
                record(TOPIC, 2, 7L, "key-a", "value-a", marker),
                KafkaBGRecordProcessor.ProcessResult.newUnprocessed(),
                exceptionHandler
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
