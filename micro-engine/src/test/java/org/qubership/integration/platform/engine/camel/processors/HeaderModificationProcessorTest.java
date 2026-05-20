package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.language.simple.SimpleLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HeaderModificationProcessorTest {

    private HeaderModificationProcessor processor;

    @Mock
    SimpleLanguage simpleInterpreter;
    @Mock
    Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.defaultExchange();
        simpleInterpreter = mock(SimpleLanguage.class);
        processor = new HeaderModificationProcessor(simpleInterpreter);
    }


    @Test
    void shouldAddHeadersWhenHeadersToAddPresent() throws Exception {
        Expression firstExpression = mock(Expression.class);
        Expression secondExpression = mock(Expression.class);

        Map<String, String> headersToAdd = new LinkedHashMap<>();
        headersToAdd.put("X-Customer-Id", "${exchangeProperty.customerId}");
        headersToAdd.put("X-Request-Source", "${exchangeProperty.requestSource}");

        exchange.setProperty(CamelConstants.Properties.HEADER_MODIFICATION_TO_ADD, headersToAdd);

        when(simpleInterpreter.createExpression("${exchangeProperty.customerId}")).thenReturn(firstExpression);
        when(simpleInterpreter.createExpression("${exchangeProperty.requestSource}")).thenReturn(secondExpression);
        when(firstExpression.evaluate(exchange, String.class)).thenReturn("C-100500");
        when(secondExpression.evaluate(exchange, String.class)).thenReturn("portal-ui");

        processor.process(exchange);

        assertEquals("C-100500", exchange.getMessage().getHeader("X-Customer-Id"));
        assertEquals("portal-ui", exchange.getMessage().getHeader("X-Request-Source"));
    }

    @Test
    void shouldSkipEmptyHeaderValuesWhenHeadersToAddPresent() throws Exception {
        Expression expression = mock(Expression.class);

        Map<String, String> headersToAdd = new LinkedHashMap<>();
        headersToAdd.put("X-Customer-Id", "${exchangeProperty.customerId}");
        headersToAdd.put("X-Empty", "");

        exchange.setProperty(CamelConstants.Properties.HEADER_MODIFICATION_TO_ADD, headersToAdd);

        when(simpleInterpreter.createExpression("${exchangeProperty.customerId}")).thenReturn(expression);
        when(expression.evaluate(exchange, String.class)).thenReturn("C-100500");

        processor.process(exchange);

        assertEquals("C-100500", exchange.getMessage().getHeader("X-Customer-Id"));
        assertNull(exchange.getMessage().getHeader("X-Empty"));
    }

    @Test
    void shouldRemoveHeadersByPatternAndKeepAddedHeaders() throws Exception {
        Expression expression = mock(Expression.class);

        Map<String, String> headersToAdd = new LinkedHashMap<>();
        headersToAdd.put("X-Keep", "${exchangeProperty.keepValue}");

        exchange.setProperty(CamelConstants.Properties.HEADER_MODIFICATION_TO_ADD, headersToAdd);
        exchange.setProperty(
                CamelConstants.Properties.HEADER_MODIFICATION_TO_REMOVE,
                List.of("X-*")
        );

        exchange.getMessage().setHeader("X-Keep", "old-value");
        exchange.getMessage().setHeader("X-Remove-1", "remove-me");
        exchange.getMessage().setHeader("X-Remove-2", "remove-me-too");
        exchange.getMessage().setHeader("Other-Header", "stay");

        when(simpleInterpreter.createExpression("${exchangeProperty.keepValue}")).thenReturn(expression);
        when(expression.evaluate(exchange, String.class)).thenReturn("new-value");

        processor.process(exchange);

        assertEquals("new-value", exchange.getMessage().getHeader("X-Keep"));
        assertNull(exchange.getMessage().getHeader("X-Remove-1"));
        assertNull(exchange.getMessage().getHeader("X-Remove-2"));
        assertEquals("stay", exchange.getMessage().getHeader("Other-Header"));
    }

    @Test
    void shouldRemoveHeadersByPatternWhenHeadersToAddAbsent() throws Exception {
        exchange.setProperty(
                CamelConstants.Properties.HEADER_MODIFICATION_TO_REMOVE,
                List.of("X-*")
        );

        exchange.getMessage().setHeader("X-Trace-Id", "trace");
        exchange.getMessage().setHeader("X-Request-Id", "request");
        exchange.getMessage().setHeader("Content-Type", "application/json");

        processor.process(exchange);

        assertNull(exchange.getMessage().getHeader("X-Trace-Id"));
        assertNull(exchange.getMessage().getHeader("X-Request-Id"));
        assertEquals("application/json", exchange.getMessage().getHeader("Content-Type"));
        verifyNoInteractions(simpleInterpreter);
    }
}
