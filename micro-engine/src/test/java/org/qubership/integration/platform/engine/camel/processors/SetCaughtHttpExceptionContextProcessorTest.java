package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SetCaughtHttpExceptionContextProcessorTest {

    private final SetCaughtHttpExceptionContextProcessor processor =
            new SetCaughtHttpExceptionContextProcessor();

    @Test
    void shouldSetResponseBodyAndHeadersWhenCaughtExceptionIsHttpOperationFailedException() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        HttpOperationFailedException exception = new HttpOperationFailedException(
                "http://example.com/api/customers",
                500,
                "Internal Server Error",
                null,
                Map.of("Content-Type", "application/json", "X-Request-Id", "REQ-42"),
                "{\"error\":\"failed\"}"
        );

        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, exception);

        processor.process(exchange);

        assertEquals("{\"error\":\"failed\"}", exchange.getMessage().getBody(String.class));
        assertEquals("application/json", exchange.getMessage().getHeader("Content-Type"));
        assertEquals("REQ-42", exchange.getMessage().getHeader("X-Request-Id"));
    }

    @Test
    void shouldDoNothingWhenCaughtExceptionIsNotHttpOperationFailedException() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new IllegalStateException("boom"));
        exchange.getMessage().setBody("initial-body");
        exchange.getMessage().setHeader("X-Original", "value");

        processor.process(exchange);

        assertEquals("initial-body", exchange.getMessage().getBody(String.class));
        assertEquals("value", exchange.getMessage().getHeader("X-Original"));
        assertNull(exchange.getMessage().getHeader("Content-Type"));
    }

    @Test
    void shouldDoNothingWhenCaughtExceptionAbsent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setBody("initial-body");
        exchange.getMessage().setHeader("X-Original", "value");

        processor.process(exchange);

        assertEquals("initial-body", exchange.getMessage().getBody(String.class));
        assertEquals("value", exchange.getMessage().getHeader("X-Original"));
    }
}
