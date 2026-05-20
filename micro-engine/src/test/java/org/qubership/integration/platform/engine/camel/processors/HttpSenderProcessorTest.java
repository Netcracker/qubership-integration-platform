package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HttpSenderProcessorTest {

    private final HttpSenderProcessor processor = new HttpSenderProcessor();

    @Test
    void shouldAddDefaultHttpProtocolWhenProtocolMissing() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setHeader(Headers.HTTP_URI, "example.com/api/customers");

        processor.process(exchange);

        assertEquals(
                "http://example.com/api/customers",
                exchange.getMessage().getHeader(Headers.HTTP_URI)
        );
    }

    @Test
    void shouldKeepHttpProtocolWhenAlreadyPresent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setHeader(Headers.HTTP_URI, "http://example.com/api/customers");

        processor.process(exchange);

        assertEquals(
                "http://example.com/api/customers",
                exchange.getMessage().getHeader(Headers.HTTP_URI)
        );
    }

    @Test
    void shouldKeepHttpsProtocolWhenAlreadyPresent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setHeader(Headers.HTTP_URI, "https://example.com/api/customers");

        processor.process(exchange);

        assertEquals(
                "https://example.com/api/customers",
                exchange.getMessage().getHeader(Headers.HTTP_URI)
        );
    }

    @Test
    void shouldEncodeUnsafeCharactersInUrl() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setHeader(
                Headers.HTTP_URI,
                "example.com/api/customers?name=John Doe"
        );

        processor.process(exchange);

        assertEquals(
                "http://example.com/api/customers?name=John%20Doe",
                exchange.getMessage().getHeader(Headers.HTTP_URI)
        );
    }
}
