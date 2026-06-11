package org.qubership.integration.platform.engine.camel.processors.context.propagation;

import org.apache.camel.Exchange;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextRestoreProcessorTest {

    @Mock
    private CamelExchangeContextPropagation camelExchangeContextPropagation;

    private ContextRestoreProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ContextRestoreProcessor(camelExchangeContextPropagation);
    }

    @Test
    void shouldActivateSnapshotRemoveContextHeadersAndRestoreAuthorizationWhenItWasRemoved() throws Exception {
        Exchange exchange = exchangeWithSnapshot();
        exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION, "Bearer asd12345");
        exchange.getMessage().setHeader("X-Request-Id", "abe5e22e-023c-49ce-96e2-2fdfb2de9d84");
        exchange.getMessage().setHeader("Sample-Header", "sample-value");

        Map<String, Object> headers = exchange.getMessage().getHeaders();
        Map<String, Object> contextSnapshot = exchange.getProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, Map.class);

        doAnswer(invocation -> {
            Map<String, Object> exchangeHeaders = invocation.getArgument(0);
            exchangeHeaders.remove(HttpHeaders.AUTHORIZATION);
            exchangeHeaders.remove("X-Request-Id");
            return null;
        }).when(camelExchangeContextPropagation).removeContextHeaders(headers);

        processor.process(exchange);

        assertEquals("Bearer asd12345", headers.get(HttpHeaders.AUTHORIZATION));
        assertEquals("sample-value", headers.get("Sample-Header"));
        assertFalse(headers.containsKey("X-Request-Id"));
        verify(camelExchangeContextPropagation).activateContextSnapshot(contextSnapshot);
        verify(camelExchangeContextPropagation).removeContextHeaders(headers);
    }

    @Test
    void shouldKeepAuthorizationWhenContextHeadersRemovalDoesNotRemoveIt() throws Exception {
        Exchange exchange = exchangeWithSnapshot();
        exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION, "Bearer asd12345");
        exchange.getMessage().setHeader("X-Request-Id", "abe5e22e-023c-49ce-96e2-2fdfb2de9d84");

        Map<String, Object> headers = exchange.getMessage().getHeaders();
        Map<String, Object> contextSnapshot = exchange.getProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, Map.class);

        doAnswer(invocation -> {
            Map<String, Object> exchangeHeaders = invocation.getArgument(0);
            exchangeHeaders.remove("X-Request-Id");
            return null;
        }).when(camelExchangeContextPropagation).removeContextHeaders(headers);

        processor.process(exchange);

        assertEquals("Bearer asd12345", headers.get(HttpHeaders.AUTHORIZATION));
        assertFalse(headers.containsKey("X-Request-Id"));
        verify(camelExchangeContextPropagation).activateContextSnapshot(contextSnapshot);
        verify(camelExchangeContextPropagation).removeContextHeaders(headers);
    }

    @Test
    void shouldNotRestoreAuthorizationWhenAuthorizationHeaderIsMissing() throws Exception {
        Exchange exchange = exchangeWithSnapshot();
        exchange.getMessage().setHeader("X-Request-Id", "abe5e22e-023c-49ce-96e2-2fdfb2de9d84");

        Map<String, Object> headers = exchange.getMessage().getHeaders();
        Map<String, Object> contextSnapshot = exchange.getProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, Map.class);

        doAnswer(invocation -> {
            Map<String, Object> exchangeHeaders = invocation.getArgument(0);
            exchangeHeaders.remove("X-Request-Id");
            return null;
        }).when(camelExchangeContextPropagation).removeContextHeaders(headers);

        processor.process(exchange);

        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
        assertFalse(headers.containsKey("X-Request-Id"));
        verify(camelExchangeContextPropagation).activateContextSnapshot(contextSnapshot);
        verify(camelExchangeContextPropagation).removeContextHeaders(headers);
    }

    @Test
    void shouldCallRestoreAdditionalHeadersWhenSnapshotExists() throws Exception {
        RecordingContextRestoreProcessor recordingProcessor =
            new RecordingContextRestoreProcessor(camelExchangeContextPropagation);
        Exchange exchange = exchangeWithSnapshot();

        recordingProcessor.process(exchange);

        assertSame(exchange, recordingProcessor.restoredExchange);
    }

    @Test
    void shouldNotChangeExchangeAndContextWhenSnapshotIsMissing() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION, "Bearer asd12345");
        exchange.getMessage().setHeader("Sample-Header", "sample-value");

        processor.process(exchange);

        assertEquals("Bearer asd12345", exchange.getMessage().getHeader(HttpHeaders.AUTHORIZATION));
        assertEquals("sample-value", exchange.getMessage().getHeader("Sample-Header"));
        verifyNoInteractions(camelExchangeContextPropagation);
    }

    private static Exchange exchangeWithSnapshot() {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, Map.of(
            "tenant", "default-tenant",
            "requestId", "abe5e22e-023c-49ce-96e2-2fdfb2de9d84"
        ));
        return exchange;
    }

    private static class RecordingContextRestoreProcessor extends ContextRestoreProcessor {

        private Exchange restoredExchange;

        private RecordingContextRestoreProcessor(CamelExchangeContextPropagation camelExchangeContextPropagation) {
            super(camelExchangeContextPropagation);
        }

        @Override
        protected void restoreAdditionalHeaders(Exchange exchange) {
            restoredExchange = exchange;
        }
    }
}
