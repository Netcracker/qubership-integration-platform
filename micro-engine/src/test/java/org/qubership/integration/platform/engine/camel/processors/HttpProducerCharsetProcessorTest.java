package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HttpProducerCharsetProcessorTest {

    private final HttpProducerCharsetProcessor processor = new HttpProducerCharsetProcessor();

    @Test
    void shouldDoNothingWhenExchangeNull() {
        assertDoesNotThrow(() -> processor.process(null));
    }

    @Test
    void shouldKeepExistingCharsetWhenCharsetPropertyAlreadyPresent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(ExchangePropertyKey.CHARSET_NAME, "windows-1251");

        processor.process(exchange);

        assertEquals(
                "windows-1251",
                exchange.getProperty(ExchangePropertyKey.CHARSET_NAME, String.class)
        );
    }

    @Test
    void shouldKeepExistingCharsetWhenContentTypeContainsCharset() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json;charset=UTF-16");

        processor.process(exchange);

        assertNull(exchange.getProperty(ExchangePropertyKey.CHARSET_NAME, String.class));
    }

    @Test
    void shouldSetDefaultCharsetWhenContentTypeHasNoCharset() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");

        processor.process(exchange);

        assertEquals(
                "UTF-8",
                exchange.getProperty(ExchangePropertyKey.CHARSET_NAME, String.class)
        );
    }

    @Test
    void shouldSetDefaultCharsetWhenContentTypeAbsent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        processor.process(exchange);

        assertEquals(
                "UTF-8",
                exchange.getProperty(ExchangePropertyKey.CHARSET_NAME, String.class)
        );
    }
}
