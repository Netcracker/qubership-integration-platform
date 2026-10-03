package org.qubership.integration.platform.engine.camel.components.kafka.producer;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Message;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultMessage;
import org.apache.camel.support.ExchangeHelper;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.camel.component.kafka.KafkaConstants.KAFKA_RECORD_META;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaCustomProducerTest {
    private KafkaCustomProducer producer;

    @Mock
    private Producer<Object, Object> kafkaProducer;
    @Mock
    private ExecutorService workerPool;
    @Captor
    private ArgumentCaptor<Callback> callbacks;

    private DefaultCamelContext camelContext;
    private KafkaCustomConfiguration configuration;
    private CompletionState completion;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
        configuration = new KafkaCustomConfiguration();
        configuration.setTopic("test-topic");
        KafkaCustomComponent component = new KafkaCustomComponent();
        component.setCamelContext(camelContext);
        KafkaCustomEndpoint endpoint = new KafkaCustomEndpoint("kafka-custom:test-topic", component);
        endpoint.setConfiguration(configuration);
        producer = new KafkaCustomProducer(endpoint);
        producer.setKafkaProducer(kafkaProducer);
        producer.setWorkerPool(workerPool);
        completion = new CompletionState();
        when(workerPool.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return CompletableFuture.completedFuture(null);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPreserveReturnedHeadersWithoutLateMetadataWritesWhenScalarSendCompletes(boolean recordMetadata) {
        configuration.setRecordMetadata(recordMetadata);
        Exchange exchange = exchange("Accepted after reset");
        Exchange returned = MockExchanges.defaultExchange(camelContext, ExchangePattern.InOut);
        RecordMetadata metadata = metadata(8);
        List<Boolean> completions = new ArrayList<>();

        assertFalse(producer.process(exchange, synchronous -> {
            completion.resumed = true;
            ExchangeHelper.copyResults(exchange, exchange);
            ExchangeHelper.copyResults(returned, exchange);
            completions.add(synchronous);
        }));
        verify(kafkaProducer).send(any(), callbacks.capture());
        assertTrue(completions.isEmpty());

        callbacks.getValue().onCompletion(metadata, null);

        assertEquals(List.of(false), completions);
        assertEquals("Accepted after reset", returned.getMessage().getBody());
        assertEquals("preserved", returned.getMessage().getHeader("X-Snapshot-Header"));
        if (recordMetadata) {
            assertEquals(List.of(metadata), returned.getMessage().getHeader(KAFKA_RECORD_META));
        } else {
            assertNull(returned.getMessage().getHeader(KAFKA_RECORD_META));
        }
        assertTrue(completion.lateHeaderWrites.isEmpty(),
                "Kafka must finish metadata writes before resuming the exchange.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCompleteBatchItemMetadataAndFailuresBeforeResumingWhenFinalAcknowledgmentArrives(boolean failure) {
        Exchange first = exchange("First item");
        Exchange second = exchange("Second item");
        Message secondMessage = second.getMessage();
        Exchange batch = exchange(List.of(first, secondMessage));
        RecordMetadata secondMetadata = metadata(9);
        RecordMetadata firstMetadata = failure ? null : metadata(8);
        TimeoutException sendFailure = failure ? new TimeoutException("Publication timed out") : null;
        List<Boolean> completions = new ArrayList<>();
        List<Object> metadataAtResume = new ArrayList<>();
        List<Exception> failuresAtResume = new ArrayList<>();

        assertFalse(producer.process(batch, synchronous -> {
            completion.resumed = true;
            metadataAtResume.add(first.getMessage().getHeader(KAFKA_RECORD_META));
            metadataAtResume.add(secondMessage.getHeader(KAFKA_RECORD_META));
            failuresAtResume.add(first.getException());
            failuresAtResume.add(batch.getException());
            completions.add(synchronous);
        }));
        verify(kafkaProducer, times(2)).send(any(), callbacks.capture());

        callbacks.getAllValues().get(1).onCompletion(secondMetadata, null);
        assertTrue(completions.isEmpty());
        callbacks.getAllValues().get(0).onCompletion(firstMetadata, sendFailure);

        assertEquals(List.of(false), completions);
        assertEquals(Collections.singletonList(firstMetadata), metadataAtResume.get(0));
        assertEquals(List.of(secondMetadata), metadataAtResume.get(1));
        assertSame(sendFailure, failuresAtResume.get(0));
        assertSame(sendFailure, failuresAtResume.get(1));
        assertEquals("First item", first.getMessage().getBody());
        assertEquals("Second item", secondMessage.getBody());
        assertEquals("preserved", first.getMessage().getHeader("X-Snapshot-Header"));
        assertEquals("preserved", secondMessage.getHeader("X-Snapshot-Header"));
        assertTrue(completion.lateHeaderWrites.isEmpty(),
                "Kafka must finish batch metadata writes before resuming the exchange.");
    }

    private Exchange exchange(Object body) {
        Exchange exchange = MockExchanges.defaultExchange(camelContext, ExchangePattern.InOut);
        exchange.setIn(new RecordingMessage(camelContext, completion));
        exchange.getMessage().setBody(body);
        exchange.getMessage().setHeader("X-Snapshot-Header", "preserved");
        return exchange;
    }

    private static RecordMetadata metadata(long offset) {
        return new RecordMetadata(new TopicPartition("test-topic", 1), offset, 0, 0, 0, 0);
    }

    private static final class CompletionState {
        private boolean resumed;
        private final List<String> lateHeaderWrites = new ArrayList<>();
    }

    private static final class RecordingMessage extends DefaultMessage {
        private final CompletionState completion;

        private RecordingMessage(CamelContext camelContext, CompletionState completion) {
            super(camelContext);
            this.completion = completion;
        }

        @Override
        public void setHeader(String name, Object value) {
            if (completion.resumed && KAFKA_RECORD_META.equals(name)) {
                completion.lateHeaderWrites.add(name);
            }
            super.setHeader(name, value);
        }

        @Override
        public DefaultMessage newInstance() {
            return new RecordingMessage(getCamelContext(), completion);
        }
    }
}
