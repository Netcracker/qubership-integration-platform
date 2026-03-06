package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainBlockingProducerTest {

    @Mock
    ChainEndpoint endpoint;
    @Mock
    ChainConsumer consumer;

    @Mock
    Processor processor;
    @Mock
    AsyncProcessor asyncProcessor;
    @Mock
    Exchange exchange;

    private ChainBlockingProducer producer;

    @BeforeEach
    void setUp() {
        producer = new ChainBlockingProducer(endpoint);
        exchange = MockExchanges.withDefaultCamelContext();
    }

    @Test
    void shouldDelegateToConsumerProcessorWhenProcessSyncAndConsumerAvailable() throws Exception {
        doReturn(consumer).when(endpoint).getConsumer();
        when(consumer.getProcessor()).thenReturn(processor);

        producer.process(exchange);

        verify(processor).process(exchange);
    }

    @Test
    void shouldThrowWhenNoConsumerAndFailIfNoConsumersTrueInSyncProcess() {
        when(endpoint.getConsumer()).thenReturn(null);
        when(endpoint.isFailIfNoConsumers()).thenReturn(true);

        assertThrows(ChainConsumerNotAvailableException.class, () -> producer.process(exchange));
    }

    @Test
    void shouldAwaitAndThenUseConsumerWhenNoConsumerInitiallyAndFailIfNoConsumersFalse() throws Exception {
        when(endpoint.getConsumer()).thenReturn(null, consumer);
        when(endpoint.isFailIfNoConsumers()).thenReturn(false);
        when(endpoint.getTimeout()).thenReturn(30_000L);

        when(consumer.getProcessor()).thenReturn(processor);

        producer.process(exchange);

        verify(processor).process(exchange);
        verify(endpoint, atLeast(2)).getConsumer();
    }

    @Test
    void shouldThrowWhenNoConsumerAfterAwaitInSyncProcess() {
        when(endpoint.getConsumer()).thenReturn(null);
        when(endpoint.isFailIfNoConsumers()).thenReturn(false);
        when(endpoint.getTimeout()).thenReturn(0L);

        assertThrows(ChainConsumerNotAvailableException.class, () -> producer.process(exchange));
    }

    @Test
    void shouldDelegateToConsumerAsyncProcessorWhenProcessAsyncAndConsumerAvailable() {
        AsyncCallback callback = mock(AsyncCallback.class);

        when(endpoint.getConsumer()).thenReturn(consumer);
        when(consumer.getAsyncProcessor()).thenReturn(asyncProcessor);
        when(asyncProcessor.process(exchange, callback)).thenReturn(false);

        boolean result = producer.process(exchange, callback);

        assertFalse(result);
        assertNull(exchange.getException());
        verify(asyncProcessor).process(exchange, callback);
    }

    @Test
    void shouldSetExceptionAndCompleteCallbackWhenAsyncProcessorThrows() {
        var exchange = MockExchanges.withDefaultCamelContext();
        AsyncCallback callback = mock(AsyncCallback.class);

        when(endpoint.getConsumer()).thenReturn(consumer);
        when(consumer.getAsyncProcessor()).thenReturn(asyncProcessor);

        RuntimeException boom = new RuntimeException("boom");
        when(asyncProcessor.process(exchange, callback)).thenThrow(boom);

        boolean result = producer.process(exchange, callback);

        assertTrue(result);
        assertSame(boom, exchange.getException());
        verify(callback).done(true);
    }

    @Test
    void shouldSetExceptionAndCompleteCallbackWhenNoConsumerAndFailIfNoConsumersTrueInAsyncProcess() {
        AsyncCallback callback = mock(AsyncCallback.class);

        when(endpoint.getConsumer()).thenReturn(null);
        when(endpoint.isFailIfNoConsumers()).thenReturn(true);

        boolean result = producer.process(exchange, callback);

        assertTrue(result);
        assertNotNull(exchange.getException());
        assertInstanceOf(ChainConsumerNotAvailableException.class, exchange.getException());
        verify(callback).done(true);
    }
}
