package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.kafka.OpenSearchKafkaProducer;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchWriterKafkaTest {

    private static final String SESSION_ID = "fd9f3d14-b5c8-4ed6-b858-84267d3335ad";
    private static final String ELEMENT_ID = "2c30495a-8e89-4763-92d9-5350bebd2aa3";

    private OpenSearchWriterKafka writer;

    @Mock
    private OpenSearchKafkaProducer openSearchKafkaProducer;

    @BeforeEach
    void setUp() {
        writer = new OpenSearchWriterKafka(openSearchKafkaProducer);
    }

    @Test
    void shouldSendKafkaQueueElementWithElementIdAsKey() {
        SessionElementElastic element = sessionElement();

        writer.scheduleElementToLog(element);

        KafkaQueueElement kafkaQueueElement = captureKafkaQueueElement();
        assertKafkaQueueElement(kafkaQueueElement, element);
        assertNull(writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    @Test
    void shouldCacheElementWhenSchedulingWithCacheEnabled() {
        SessionElementElastic element = sessionElement();

        writer.scheduleElementToLog(element, true);

        KafkaQueueElement kafkaQueueElement = captureKafkaQueueElement();
        assertKafkaQueueElement(kafkaQueueElement, element);
        assertSame(element, writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    @Test
    void shouldSendAndCacheElementWhenSessionIsAlive() {
        SessionElementElastic element = sessionElement();
        writer.putSessionToCache(Session.builder()
                .id(SESSION_ID)
                .build());

        writer.scheduleElementToLogAndCache(element);

        KafkaQueueElement kafkaQueueElement = captureKafkaQueueElement();
        assertKafkaQueueElement(kafkaQueueElement, element);
        assertSame(element, writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    @Test
    void shouldSendCancelledElementWithoutCachingWhenSessionIsNotAlive() {
        SessionElementElastic element = sessionElement();

        writer.scheduleElementToLogAndCache(element);

        KafkaQueueElement kafkaQueueElement = captureKafkaQueueElement();
        assertKafkaQueueElement(kafkaQueueElement, element);
        assertEquals(ExecutionStatus.CANCELLED_OR_UNKNOWN, element.getExecutionStatus());
        assertNull(writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    @Test
    void shouldPropagateProducerExceptionAndNotCacheElementWhenSendFails() {
        SessionElementElastic element = sessionElement();
        RuntimeException exception = new RuntimeException("send failed");
        doThrow(exception).when(openSearchKafkaProducer)
                .send(eq(ELEMENT_ID), any(KafkaQueueElement.class));

        RuntimeException result = assertThrows(
                RuntimeException.class,
                () -> writer.scheduleElementToLog(element, true)
        );

        assertSame(exception, result);
        assertNull(writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    private SessionElementElastic sessionElement() {
        return SessionElementElastic.builder()
                .id(ELEMENT_ID)
                .sessionId(SESSION_ID)
                .chainId("chain-1")
                .bodyBefore("body-before")
                .bodyAfter("body-after")
                .build();
    }

    private KafkaQueueElement captureKafkaQueueElement() {
        ArgumentCaptor<KafkaQueueElement> queueElementCaptor = ArgumentCaptor.forClass(KafkaQueueElement.class);
        verify(openSearchKafkaProducer).send(eq(OpenSearchWriterKafkaTest.ELEMENT_ID), queueElementCaptor.capture());
        return queueElementCaptor.getValue();
    }

    private void assertKafkaQueueElement(KafkaQueueElement kafkaQueueElement, SessionElementElastic element) {
        assertEquals(element.getId(), kafkaQueueElement.getId());
        assertSame(element, kafkaQueueElement.getSource());
    }
}
