package org.qubership.integration.platform.engine.kafka;

import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DefaultOpenSearchKafkaProducerTest {

    private static final String KEY = "session-id";

    @Mock
    private Emitter<Record<String, KafkaQueueElement>> emitter;

    @Mock
    private KafkaQueueElement kafkaQueueElement;

    private DefaultOpenSearchKafkaProducer producer;

    @BeforeEach
    void setUp() {
        producer = new DefaultOpenSearchKafkaProducer();
        producer.emitter = emitter;
    }

    @Test
    void shouldSendKafkaQueueElementWithProvidedKey() {
        producer.send(KEY, kafkaQueueElement);

        ArgumentCaptor<Record<String, KafkaQueueElement>> recordCaptor = recordCaptor();
        verify(emitter).send(recordCaptor.capture());

        Record<String, KafkaQueueElement> record = recordCaptor.getValue();
        assertEquals(KEY, record.key());
        assertSame(kafkaQueueElement, record.value());
    }

    @Test
    void shouldNotPropagateExceptionWhenEmitterFails() {
        RuntimeException exception = new RuntimeException("Failed to send Kafka record");

        doThrow(exception).when(emitter).send((Record<String, KafkaQueueElement>) any());

        assertDoesNotThrow(() -> producer.send(KEY, kafkaQueueElement));

        verify(emitter).send((Record<String, KafkaQueueElement>) any());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Record<String, KafkaQueueElement>> recordCaptor() {
        return ArgumentCaptor.forClass(Record.class);
    }
}
