package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainProcessorTest {

    private ChainComponent component;
    private CamelContext endpointContext;
    private ChainEndpoint endpoint;

    @BeforeEach
    void setUp() {
        component = new ChainComponent();
        endpointContext = new DefaultCamelContext();
        component.setCamelContext(endpointContext);
        endpoint = new ChainEndpoint("cip-chain:routeA", component);
    }

    @Test
    void shouldSetChainCallTriggeredSessionToTrueDuringProcessingAndRestoreAfterDoneWhenPreviouslyFalse() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext(), ExchangePattern.InOut);
        exchange.getMessage().setBody("in");
        exchange.setProperty(CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, false);

        AsyncProcessor delegate = asyncProcessor((ex, cb) -> {
            assertSame(endpointContext, ex.getContext());
            assertSame(endpoint, ex.getFromEndpoint());

            assertEquals(true, ex.getProperty(
                    CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, Boolean.class));

            ex.getMessage().setBody("changed");
            cb.done(true);
            return true;
        });

        ChainProcessor processor = new ChainProcessor(delegate, endpoint);

        CapturingCallback callback = new CapturingCallback();
        boolean result = processor.process(exchange, callback);

        assertTrue(result);
        assertEquals(true, callback.doneValue);

        assertEquals("changed", exchange.getMessage().getBody(String.class));
        assertEquals(false, exchange.getProperty(
                CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, Boolean.class));
    }

    @Test
    void shouldRestoreChainCallTriggeredSessionToTrueAfterDoneWhenPreviouslyTrue() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext(), ExchangePattern.InOut);
        exchange.setProperty(CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, true);

        AsyncProcessor delegate = asyncProcessor((ex, cb) -> {
            assertEquals(true, ex.getProperty(
                    CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, Boolean.class));
            cb.done(true);
            return true;
        });

        ChainProcessor processor = new ChainProcessor(delegate, endpoint);

        CapturingCallback callback = new CapturingCallback();
        processor.process(exchange, callback);

        assertEquals(true, callback.doneValue);
        assertEquals(true, exchange.getProperty(
                CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, Boolean.class));
    }

    @Test
    void shouldCopyExceptionBackToOriginalExchange() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext(), ExchangePattern.InOut);

        IllegalStateException boom = new IllegalStateException("boom");
        AsyncProcessor delegate = asyncProcessor((ex, cb) -> {
            ex.setException(boom);
            cb.done(true);
            return true;
        });

        ChainProcessor processor = new ChainProcessor(delegate, endpoint);

        CapturingCallback callback = new CapturingCallback();
        processor.process(exchange, callback);

        assertEquals(true, callback.doneValue);
        assertSame(boom, exchange.getException());
    }

    @Test
    void shouldSwitchAndRestoreThreadContextClassLoaderWhenApplicationContextClassLoaderProvided() {
        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        ClassLoader appCl = new ClassLoader() {
        };

        CamelContext ctxWithAppCl = new DefaultCamelContext() {
            @Override
            public ClassLoader getApplicationContextClassLoader() {
                return appCl;
            }
        };

        component.setCamelContext(ctxWithAppCl);
        endpoint = new ChainEndpoint("cip-chain:routeA", component);

        Exchange exchange = new DefaultExchange(new DefaultCamelContext(), ExchangePattern.InOut);

        AsyncProcessor delegate = asyncProcessor((ex, cb) -> {
            assertSame(appCl, Thread.currentThread().getContextClassLoader());
            cb.done(true);
            return true;
        });

        ChainProcessor processor = new ChainProcessor(delegate, endpoint);

        CapturingCallback callback = new CapturingCallback();
        try {
            processor.process(exchange, callback);
            assertEquals(true, callback.doneValue);
        } finally {
            assertSame(originalCl, Thread.currentThread().getContextClassLoader());
        }
    }

    @FunctionalInterface
    private interface AsyncHandler {
        boolean handle(Exchange exchange, AsyncCallback callback);
    }

    private static AsyncProcessor asyncProcessor(AsyncHandler handler) {
        return new AsyncProcessor() {
            @Override
            public void process(Exchange exchange) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public boolean process(Exchange exchange, AsyncCallback callback) {
                return handler.handle(exchange, callback);
            }

            @Override
            public CompletableFuture<Exchange> processAsync(Exchange exchange) {
                return CompletableFuture.completedFuture(exchange);
            }
        };
    }

    private static final class CapturingCallback implements AsyncCallback {
        Boolean doneValue;

        @Override
        public void done(boolean done) {
            this.doneValue = done;
        }
    }
}
