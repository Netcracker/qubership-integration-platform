package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RequestFilterProcessorTest {

    private static final String REQUEST_FILTER_PROPERTY_NAME =
            CamelConstants.INTERNAL_PROPERTY_PREFIX + "requestFilterHeader";

    private final RequestFilterProcessor processor =
            new RequestFilterProcessor("requestFilterHeader");

    @Test
    void shouldDoNothingWhenHeaderAllowListAbsent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        processor.process(exchange);

        assertFalse(exchange.getExchangeExtension().isInterrupted());
    }

    @Test
    void shouldNotInterruptWhenAllRequiredHeadersPresentAndMatching() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Map<String, String> headerAllowList = new LinkedHashMap<>();
        headerAllowList.put("X-Request-Source", "portal-ui");
        headerAllowList.put("X-Customer-Id", "");

        exchange.setProperty(REQUEST_FILTER_PROPERTY_NAME, headerAllowList);
        exchange.getMessage().setHeader("X-Request-Source", "portal-ui");
        exchange.getMessage().setHeader("X-Customer-Id", "C-100500");

        processor.process(exchange);

        assertFalse(exchange.getExchangeExtension().isInterrupted());
    }

    @Test
    void shouldInterruptWhenRequiredHeaderMissing() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Map<String, String> headerAllowList = Map.of(
                "X-Request-Source", "portal-ui"
        );

        exchange.setProperty(REQUEST_FILTER_PROPERTY_NAME, headerAllowList);

        processor.process(exchange);

        assertTrue(exchange.getExchangeExtension().isInterrupted());
    }

    @Test
    void shouldInterruptWhenHeaderValueDoesNotMatch() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Map<String, String> headerAllowList = Map.of(
                "X-Request-Source", "portal-ui"
        );

        exchange.setProperty(REQUEST_FILTER_PROPERTY_NAME, headerAllowList);
        exchange.getMessage().setHeader("X-Request-Source", "mobile-app");

        processor.process(exchange);

        assertTrue(exchange.getExchangeExtension().isInterrupted());
    }

    @Test
    void shouldNotCheckHeaderValueWhenAllowListValueBlank() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Map<String, String> headerAllowList = Map.of(
                "X-Customer-Id", " "
        );

        exchange.setProperty(REQUEST_FILTER_PROPERTY_NAME, headerAllowList);
        exchange.getMessage().setHeader("X-Customer-Id", "C-100500");

        processor.process(exchange);

        assertFalse(exchange.getExchangeExtension().isInterrupted());
    }
}
