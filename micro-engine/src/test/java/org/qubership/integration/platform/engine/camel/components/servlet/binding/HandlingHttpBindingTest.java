package org.qubership.integration.platform.engine.camel.components.servlet.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomFilterStrategy;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HandlingHttpBindingTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private ServletCustomFilterStrategy servletCustomFilterStrategy;

    @Test
    void shouldCopyProtocolHeadersAndDelegateWhenWriteResponseSucceeds() throws Exception {
        TrackingHandlingHttpBinding binding = new TrackingHandlingHttpBinding(servletCustomFilterStrategy, true);

        Exchange exchange = mock(Exchange.class);
        Message inMessage = mock(Message.class);
        Message outMessage = mock(Message.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(exchange.getMessage()).thenReturn(outMessage);
        when(exchange.isFailed()).thenReturn(false);
        when(exchange.hasOut()).thenReturn(true);
        when(exchange.getIn()).thenReturn(inMessage);
        when(exchange.getOut()).thenReturn(outMessage);

        when(inMessage.getHeader(Exchange.CONTENT_ENCODING)).thenReturn("gzip");
        when(inMessage.getHeader(Exchange.CONTENT_ENCODING, String.class)).thenReturn("gzip");
        when(outMessage.getExchange()).thenReturn(exchange);

        binding.writeResponse(exchange, response);

        verify(outMessage).setHeader(Exchange.CONTENT_ENCODING, "gzip");
        verify(outMessage).setHeader(Exchange.TRANSFER_ENCODING, "chunked");

        assertSame(outMessage, binding.capturedResponseMessage);
        assertSame(response, binding.capturedHttpServletResponse);
        assertSame(exchange, binding.capturedExchange);
    }

    @Test
    void shouldDelegateToExceptionResponseWhenExchangeFailedWithException() throws Exception {
        TrackingHandlingHttpBinding binding = new TrackingHandlingHttpBinding(servletCustomFilterStrategy, false);

        RuntimeException exception = new RuntimeException("boom");
        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(exchange.getMessage()).thenReturn(message);
        when(exchange.isFailed()).thenReturn(true);
        when(exchange.getException()).thenReturn(exception);

        binding.writeResponse(exchange, response);

        assertSame(exception, binding.capturedException);
        assertSame(exchange, binding.capturedExchange);
        assertSame(response, binding.capturedHttpServletResponse);
    }

    @Test
    void shouldDelegateToFaultResponseWhenExchangeFailedWithoutException() throws Exception {
        TrackingHandlingHttpBinding binding = new TrackingHandlingHttpBinding(servletCustomFilterStrategy, false);

        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(exchange.getMessage()).thenReturn(message);
        when(exchange.isFailed()).thenReturn(true);
        when(exchange.getException()).thenReturn(null);

        binding.writeResponse(exchange, response);

        assertSame(message, binding.capturedFaultMessage);
        assertSame(exchange, binding.capturedExchange);
        assertSame(response, binding.capturedHttpServletResponse);
    }

    @Test
    void shouldSendInternalServerErrorWhenDoWriteExceptionResponseWithoutExchange() throws Exception {
        HandlingHttpBinding binding = new HandlingHttpBinding(servletCustomFilterStrategy);
        HttpServletResponse response = mock(HttpServletResponse.class);

        binding.doWriteExceptionResponse(new RuntimeException("boom"), response, null);

        verify(response).sendError(500);
    }

    @Test
    void shouldWriteExchangeMessageWhenDoWriteExceptionResponseWithExchange() throws Exception {
        HandlingHttpBinding binding = new HandlingHttpBinding(servletCustomFilterStrategy);

        Exchange exchange = MockExchanges.defaultExchange();
        exchange.getMessage().setBody("{\"error\":\"boom\"}");

        ResponseCapture responseCapture = new ResponseCapture();

        binding.doWriteExceptionResponse(new RuntimeException("boom"), responseCapture.response, exchange);

        verify(responseCapture.response, never()).sendError(anyInt());
        assertTrue(responseCapture.bodyAsString().contains("boom"));
    }

    @Test
    void shouldFilterIncludedFieldsWhenDoWriteResponseCalled() throws Exception {
        HandlingHttpBinding binding = new HandlingHttpBinding(servletCustomFilterStrategy);

        Exchange exchange = MockExchanges.defaultExchange();
        Message message = exchange.getMessage();
        message.setBody("{\"name\":\"Alex\",\"secret\":\"123\",\"nested\":{\"visible\":1,\"hidden\":2}}");
        exchange.setProperty(
                CamelConstants.Properties.RESPONSE_FILTER_INCLUDE_FIELDS,
                "name, nested.visible"
        );

        ResponseCapture responseCapture = new ResponseCapture();

        binding.doWriteResponse(message, responseCapture.response, exchange);

        assertEquals(
                OBJECT_MAPPER.readTree("{\"name\":\"Alex\",\"nested\":{\"visible\":1}}"),
                OBJECT_MAPPER.readTree(message.getBody(String.class))
        );
    }

    @Test
    void shouldFilterExcludedFieldsWhenDoWriteResponseCalled() throws Exception {
        HandlingHttpBinding binding = new HandlingHttpBinding(servletCustomFilterStrategy);

        Exchange exchange = MockExchanges.defaultExchange();
        Message message = exchange.getMessage();
        message.setBody("{\"name\":\"Alex\",\"secret\":\"123\",\"nested\":{\"visible\":1,\"hidden\":2}}");
        exchange.setProperty(
                CamelConstants.Properties.RESPONSE_FILTER_EXCLUDE_FIELDS,
                "secret, nested.hidden"
        );

        ResponseCapture responseCapture = new ResponseCapture();

        binding.doWriteResponse(message, responseCapture.response, exchange);

        assertEquals(
                OBJECT_MAPPER.readTree("{\"name\":\"Alex\",\"nested\":{\"visible\":1}}"),
                OBJECT_MAPPER.readTree(message.getBody(String.class))
        );
    }

    @Test
    void shouldNotFilterBodyWhenExchangeFailed() throws Exception {
        HandlingHttpBinding binding = new HandlingHttpBinding(servletCustomFilterStrategy);

        Exchange exchange = MockExchanges.defaultExchange();
        Message message = exchange.getMessage();
        message.setBody("{\"name\":\"Alex\",\"secret\":\"123\"}");
        exchange.setProperty(
                CamelConstants.Properties.RESPONSE_FILTER_EXCLUDE_FIELDS,
                "secret"
        );
        exchange.setException(new RuntimeException("boom"));

        ResponseCapture responseCapture = new ResponseCapture();

        binding.doWriteResponse(message, responseCapture.response, exchange);

        assertEquals(
                OBJECT_MAPPER.readTree("{\"name\":\"Alex\",\"secret\":\"123\"}"),
                OBJECT_MAPPER.readTree(message.getBody(String.class))
        );
    }

    private static class TrackingHandlingHttpBinding extends HandlingHttpBinding {
        private final boolean chunked;

        private Throwable capturedException;
        private Message capturedFaultMessage;
        private Message capturedResponseMessage;
        private Exchange capturedExchange;
        private HttpServletResponse capturedHttpServletResponse;

        private TrackingHandlingHttpBinding(
                ServletCustomFilterStrategy servletCustomFilterStrategy,
                boolean chunked
        ) {
            super(servletCustomFilterStrategy);
            this.chunked = chunked;
        }

        @Override
        protected boolean checkChunked(Message message, Exchange exchange) {
            return chunked;
        }

        @Override
        public void doWriteExceptionResponse(Throwable exception, HttpServletResponse response, Exchange exchange)
                throws IOException {
            this.capturedException = exception;
            this.capturedExchange = exchange;
            this.capturedHttpServletResponse = response;
        }

        @Override
        public void doWriteFaultResponse(Message message, HttpServletResponse response, Exchange exchange)
                throws IOException {
            this.capturedFaultMessage = message;
            this.capturedExchange = exchange;
            this.capturedHttpServletResponse = response;
        }

        @Override
        public void doWriteResponse(Message message, HttpServletResponse response, Exchange exchange)
                throws IOException {
            this.capturedResponseMessage = message;
            this.capturedExchange = exchange;
            this.capturedHttpServletResponse = response;
        }
    }

    private static class ResponseCapture {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final HttpServletResponse response;

        private ResponseCapture() throws IOException {
            this.response = mock(HttpServletResponse.class, withSettings().lenient());

            ServletOutputStream outputStream = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                    // no-op
                }

                @Override
                public void write(int b) {
                    body.write(b);
                }
            };

            lenient().when(response.getOutputStream()).thenReturn(outputStream);
            lenient().when(response.getWriter()).thenReturn(
                    new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true)
            );
            lenient().when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8.name());
        }

        private String bodyAsString() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }
}
