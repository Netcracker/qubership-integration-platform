package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainProducerTest {

    private ChainProducer producer;

    @Mock
    ChainEndpoint endpoint;
    @Mock
    ChainComponent component;
    @Mock
    ChainConsumer consumer;
    @Mock
    AsyncProcessor asyncProcessor;
    @Mock
    HeaderFilterStrategy headerFilterStrategy;
    @Mock
    Exchange exchange;
    @Mock
    AsyncCallback callback;

    @BeforeEach
    void setUp() {
        producer = new ChainProducer(endpoint);
        exchange = MockExchanges.defaultExchange();
        callback = mock(AsyncCallback.class);

        when(endpoint.getComponent()).thenReturn(component);
    }

    @Test
    void shouldSetExceptionAndCompleteCallbackWhenNoConsumerAndFailIfNoConsumersTrue() {
        when(component.getConsumer(endpoint)).thenReturn(null);
        when(endpoint.isFailIfNoConsumers()).thenReturn(true);

        boolean result = producer.process(exchange, callback);

        assertTrue(result);
        assertNotNull(exchange.getException());
        assertInstanceOf(ChainConsumerNotAvailableException.class, exchange.getException());
        verify(callback).done(true);
    }

    @Test
    void shouldIgnoreMessageAndCompleteCallbackWhenNoConsumerAndFailIfNoConsumersFalse() {
        when(component.getConsumer(endpoint)).thenReturn(null);
        when(endpoint.isFailIfNoConsumers()).thenReturn(false);

        boolean result = producer.process(exchange, callback);

        assertTrue(result);
        assertNull(exchange.getException());
        verify(callback).done(true);
    }

    @Test
    void shouldProcessOnSameExchangeWhenPropagatePropertiesTrueAndNoHeaderFilter() {
        exchange.getProperties().put("p", "v");
        exchange.getIn().setHeader("h", "v");

        when(component.getConsumer(endpoint)).thenReturn(consumer);
        when(consumer.getAsyncProcessor()).thenReturn(asyncProcessor);

        when(endpoint.isPropagateProperties()).thenReturn(true);
        when(endpoint.getHeaderFilterStrategy()).thenReturn(null);

        when(asyncProcessor.process(any(Exchange.class), any(AsyncCallback.class))).thenAnswer(inv -> {
            Exchange submitted = inv.getArgument(0);
            AsyncCallback doneCb = inv.getArgument(1);

            assertSame(exchange, submitted);

            submitted.getMessage().setHeader("resp", "ok");
            doneCb.done(false);
            return false;
        });

        boolean result = producer.process(exchange, callback);

        assertFalse(result);
        verify(asyncProcessor).process(any(Exchange.class), any(AsyncCallback.class));
        verify(callback).done(false);
        assertEquals("v", exchange.getProperties().get("p"));
    }

    @Test
    void shouldCopyExchangeAndNotPropagatePropertiesWhenPropagatePropertiesFalse() {
        exchange.getProperties().put("p", "v");
        exchange.getIn().setHeader("h", "v");

        when(component.getConsumer(endpoint)).thenReturn(consumer);
        when(consumer.getAsyncProcessor()).thenReturn(asyncProcessor);

        when(endpoint.isPropagateProperties()).thenReturn(false);
        when(endpoint.getHeaderFilterStrategy()).thenReturn(null);

        when(asyncProcessor.process(any(Exchange.class), any(AsyncCallback.class))).thenAnswer(inv -> {
            Exchange submitted = inv.getArgument(0);
            AsyncCallback doneCb = inv.getArgument(1);

            assertNotSame(exchange, submitted);
            assertTrue(submitted.getProperties().isEmpty(),
                    "submitted properties must be cleared when propagateProperties=false");

            submitted.getMessage().setHeader("resp", "ok");
            submitted.setException(new IllegalStateException("boom"));

            doneCb.done(true);
            return true;
        });

        boolean result = producer.process(exchange, callback);

        assertTrue(result);

        assertNotNull(exchange.getException());
        assertInstanceOf(IllegalStateException.class, exchange.getException());

        assertEquals("ok", exchange.getMessage().getHeader("resp"));

        assertEquals("v", exchange.getProperties().get("p"));

        verify(asyncProcessor).process(any(Exchange.class), any(AsyncCallback.class));
        verify(callback).done(true);
    }

    @Test
    void shouldFilterHeadersUsingHeaderFilterStrategy() {
        exchange.getIn().setHeader("keep", "1");
        exchange.getIn().setHeader("removeCamel", "x");

        when(component.getConsumer(endpoint)).thenReturn(consumer);
        when(consumer.getAsyncProcessor()).thenReturn(asyncProcessor);

        when(endpoint.isPropagateProperties()).thenReturn(true);
        when(endpoint.getHeaderFilterStrategy()).thenReturn(headerFilterStrategy);

        when(headerFilterStrategy.applyFilterToCamelHeaders(eq("removeCamel"), any(), any(Exchange.class))).thenReturn(true);
        when(headerFilterStrategy.applyFilterToCamelHeaders(eq("keep"), any(), any(Exchange.class))).thenReturn(false);

        when(headerFilterStrategy.applyFilterToExternalHeaders(eq("removeExternal"), any(), any(Exchange.class))).thenReturn(true);
        when(headerFilterStrategy.applyFilterToExternalHeaders(eq("keepExternal"), any(), any(Exchange.class))).thenReturn(false);

        when(asyncProcessor.process(any(Exchange.class), any(AsyncCallback.class))).thenAnswer(inv -> {
            Exchange submitted = inv.getArgument(0);
            AsyncCallback doneCb = inv.getArgument(1);

            assertNotSame(exchange, submitted);

            Map<String, Object> submittedHeaders = submitted.getIn().getHeaders();
            assertTrue(submittedHeaders.containsKey("keep"));
            assertFalse(submittedHeaders.containsKey("removeCamel"));

            Message msg = submitted.getMessage();
            msg.setHeader("removeExternal", "x");
            msg.setHeader("keepExternal", "y");

            doneCb.done(true);
            return true;
        });

        boolean result = producer.process(exchange, callback);

        assertTrue(result);

        assertNull(exchange.getMessage().getHeader("removeExternal"));
        assertEquals("y", exchange.getMessage().getHeader("keepExternal"));

        assertNull(exchange.getMessage().getHeader("removeCamel"));
        assertEquals("1", exchange.getMessage().getHeader("keep"));

        verify(asyncProcessor).process(any(Exchange.class), any(AsyncCallback.class));
        verify(callback).done(true);
        verify(headerFilterStrategy, atLeastOnce()).applyFilterToCamelHeaders(anyString(), any(), any(Exchange.class));
        verify(headerFilterStrategy, atLeastOnce()).applyFilterToExternalHeaders(anyString(), any(), any(Exchange.class));
    }

    @Test
    void shouldSetExceptionAndCompleteCallbackWhenAsyncProcessorThrows() {
        when(component.getConsumer(endpoint)).thenReturn(consumer);
        when(consumer.getAsyncProcessor()).thenReturn(asyncProcessor);

        when(endpoint.isPropagateProperties()).thenReturn(true);
        when(endpoint.getHeaderFilterStrategy()).thenReturn(null);

        RuntimeException boom = new RuntimeException("boom");
        when(asyncProcessor.process(any(Exchange.class), any(AsyncCallback.class))).thenThrow(boom);

        boolean result = producer.process(exchange, callback);

        assertTrue(result);
        assertSame(boom, exchange.getException());
        verify(asyncProcessor).process(any(Exchange.class), any(AsyncCallback.class));
        verify(callback).done(true);
    }
}
