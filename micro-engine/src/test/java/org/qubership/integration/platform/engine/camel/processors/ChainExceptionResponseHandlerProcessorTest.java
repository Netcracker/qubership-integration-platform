package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.debugger.util.ChainExceptionResponseHandlerService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainExceptionResponseHandlerProcessorTest {

    private final ChainExceptionResponseHandlerService handler = mock(ChainExceptionResponseHandlerService.class);
    private final ChainExceptionResponseHandlerProcessor processor =
            new ChainExceptionResponseHandlerProcessor(handler);

    @Test
    void shouldHandleCaughtExceptionResponseWhenExceptionPresent() throws Exception {
        Exchange exchange = mock(Exchange.class);
        Exception exception = new IllegalStateException("Something went wrong");

        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)).thenReturn(exception);

        processor.process(exchange);

        verify(handler).handleExceptionResponse(exchange, exception);
    }

    @Test
    void shouldHandleCaughtExceptionResponseWhenExceptionAbsent() throws Exception {
        Exchange exchange = mock(Exchange.class);

        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)).thenReturn(null);

        processor.process(exchange);

        verify(handler).handleExceptionResponse(exchange, null);
    }
}
