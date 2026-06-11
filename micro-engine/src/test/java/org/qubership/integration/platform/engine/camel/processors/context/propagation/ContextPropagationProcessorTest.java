package org.qubership.integration.platform.engine.camel.processors.context.propagation;

import org.apache.camel.Exchange;
import org.apache.hc.core5.http.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.OVERRIDE_CONTEXT_PARAMS;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextPropagationProcessorTest {

    @Mock
    private CamelExchangeContextPropagation camelExchangeContextPropagation;

    private ContextPropagationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ContextPropagationProcessor(camelExchangeContextPropagation);
    }

    @Test
    void shouldOverrideHeadersForCurrentContextWhenOverrideMapExists() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Map<String, Object> overrideMap = Map.of(
            "X-Tenant", "override-tenant",
            "X-Request-Id", "abe5e22e-023c-49ce-96e2-2fdfb2de9d84"
        );
        exchange.setProperty(OVERRIDE_CONTEXT_PARAMS, overrideMap);

        Map<String, Object> headers = exchange.getMessage().getHeaders();

        processor.process(exchange);

        verify(camelExchangeContextPropagation).overrideHeadersForCurrentContext(overrideMap);
        verify(camelExchangeContextPropagation).removeContextHeaders(headers);
        verify(camelExchangeContextPropagation).propagateExchangeHeaders(headers);
    }

    @Test
    void shouldNotOverrideHeadersForCurrentContextWhenOverrideMapIsMissing() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Map<String, Object> headers = exchange.getMessage().getHeaders();

        processor.process(exchange);

        verify(camelExchangeContextPropagation, never()).overrideHeadersForCurrentContext(
            org.mockito.ArgumentMatchers.anyMap()
        );
        verify(camelExchangeContextPropagation).removeContextHeaders(headers);
        verify(camelExchangeContextPropagation).propagateExchangeHeaders(headers);
    }

    @Test
    void shouldRemoveContextHeadersPropagateExchangeHeadersAndRestoreOriginalAuthorization() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION, "Bearer asd12345");
        exchange.getMessage().setHeader("X-Request-Id", "abe5e22e-023c-49ce-96e2-2fdfb2de9d84");
        exchange.getMessage().setHeader("Other-Header", "other-value");

        Map<String, Object> headers = exchange.getMessage().getHeaders();

        doAnswer(invocation -> {
            Map<String, Object> exchangeHeaders = invocation.getArgument(0);
            exchangeHeaders.remove(HttpHeaders.AUTHORIZATION);
            exchangeHeaders.remove("X-Request-Id");
            return null;
        }).when(camelExchangeContextPropagation).removeContextHeaders(headers);

        doAnswer(invocation -> {
            Map<String, Object> exchangeHeaders = invocation.getArgument(0);
            exchangeHeaders.put(HttpHeaders.AUTHORIZATION, "Bearer 54321dsa");
            exchangeHeaders.put("X-Tenant", "default-tenant");
            return null;
        }).when(camelExchangeContextPropagation).propagateExchangeHeaders(headers);

        processor.process(exchange);

        assertEquals("Bearer asd12345", headers.get(HttpHeaders.AUTHORIZATION));
        assertEquals("default-tenant", headers.get("X-Tenant"));
        assertEquals("other-value", headers.get("Other-Header"));
        assertFalse(headers.containsKey("X-Request-Id"));

        InOrder inOrder = inOrder(camelExchangeContextPropagation);
        inOrder.verify(camelExchangeContextPropagation).removeContextHeaders(headers);
        inOrder.verify(camelExchangeContextPropagation).propagateExchangeHeaders(headers);
    }

    @Test
    void shouldNotAddAuthorizationWhenOriginalAuthorizationIsMissing() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.getMessage().setHeader("X-Request-Id", "abe5e22e-023c-49ce-96e2-2fdfb2de9d84");

        Map<String, Object> headers = exchange.getMessage().getHeaders();

        doAnswer(invocation -> {
            Map<String, Object> exchangeHeaders = invocation.getArgument(0);
            exchangeHeaders.remove("X-Request-Id");
            return null;
        }).when(camelExchangeContextPropagation).removeContextHeaders(headers);

        processor.process(exchange);

        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
        assertFalse(headers.containsKey("X-Request-Id"));
        verify(camelExchangeContextPropagation).removeContextHeaders(headers);
        verify(camelExchangeContextPropagation).propagateExchangeHeaders(headers);
    }

    @Test
    void shouldNotThrowWhenContextPropagationFails() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Map<String, Object> overrideMap = Map.of("X-Tenant", "override-tenant");
        exchange.setProperty(OVERRIDE_CONTEXT_PARAMS, overrideMap);

        doThrow(new RuntimeException("Context override failed"))
            .when(camelExchangeContextPropagation)
            .overrideHeadersForCurrentContext(overrideMap);

        assertDoesNotThrow(() -> processor.process(exchange));

        verify(camelExchangeContextPropagation).overrideHeadersForCurrentContext(overrideMap);
        verifyNoMoreInteractions(camelExchangeContextPropagation);
    }
}
