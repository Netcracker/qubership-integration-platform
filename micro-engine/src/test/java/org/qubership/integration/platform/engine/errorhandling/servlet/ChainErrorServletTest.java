package org.qubership.integration.platform.engine.errorhandling.servlet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.errorhandling.ErrorEntry;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainErrorServletTest {

    private static final String CAMEL_ROUTES_PREFIX = "/chains";
    private static final String CHAIN_REQUEST_URI = "/chains/deployment-id/some-endpoint";
    private static final String NON_CHAIN_REQUEST_URI = "/api/v1/deployments";
    private static final String RESPONSE_BODY = "{\"code\":\"error\"}";

    @Mock
    private ObjectMapper jsonMapper;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private PrintWriter writer;

    private ChainErrorServlet servlet;

    @BeforeEach
    void setUp() {
        servlet = new ChainErrorServlet();
        servlet.camelRoutesPrefix = CAMEL_ROUTES_PREFIX;
        servlet.jsonMapper = jsonMapper;
    }

    @Test
    void shouldWriteMethodNotAllowedErrorForChainRequestWithStatus405() throws Exception {
        stubChainRequest(CHAIN_REQUEST_URI);
        stubResponseStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        stubResponseBodyWriting();

        when(request.getMethod()).thenReturn("POST");

        servlet.service(request, response);

        ErrorEntry errorEntry = captureErrorEntry();

        assertEquals(ErrorCode.METHOD_NOT_ALLOWED.getFormattedCode(), errorEntry.getCode());
        assertEquals(ErrorCode.METHOD_NOT_ALLOWED.getPayload().getReason(), errorEntry.getReason());
        assertEquals("POST", errorEntry.getExtra().get("httpMethod"));

        verify(response).setStatus(ErrorCode.METHOD_NOT_ALLOWED.getHttpErrorCode());
        verify(writer).print(RESPONSE_BODY);
    }

    @Test
    void shouldWriteChainEndpointNotFoundErrorForChainRequestWithStatus404() throws Exception {
        stubChainRequest(CHAIN_REQUEST_URI);
        stubResponseStatus(HttpServletResponse.SC_NOT_FOUND);
        stubResponseBodyWriting();

        servlet.service(request, response);

        ErrorEntry errorEntry = captureErrorEntry();

        assertEquals(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND.getFormattedCode(), errorEntry.getCode());
        assertEquals(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND.getPayload().getReason(), errorEntry.getReason());

        verify(response).setStatus(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND.getHttpErrorCode());
        verify(writer).print(RESPONSE_BODY);
    }

    @Test
    void shouldWriteUnexpectedBusinessErrorForChainRequestWithUnhandledErrorStatus() throws Exception {
        stubChainRequest(CHAIN_REQUEST_URI);
        stubResponseStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        stubResponseBodyWriting();

        servlet.service(request, response);

        ErrorEntry errorEntry = captureErrorEntry();

        assertEquals(ErrorCode.UNEXPECTED_BUSINESS_ERROR.getFormattedCode(), errorEntry.getCode());
        assertEquals(ErrorCode.UNEXPECTED_BUSINESS_ERROR.getPayload().getReason(), errorEntry.getReason());

        verify(response).setStatus(ErrorCode.UNEXPECTED_BUSINESS_ERROR.getHttpErrorCode());
        verify(writer).print(RESPONSE_BODY);
    }

    @Test
    void shouldDoNothingWhenResponseStatusIsNotError() throws Exception {
        stubResponseStatus(HttpServletResponse.SC_OK);

        servlet.service(request, response);

        verify(response, never()).setStatus(any(Integer.class));
        verifyNoInteractions(jsonMapper, writer);
    }

    @Test
    void shouldDoNothingWhenRequestIsNotChainRequest() throws Exception {
        stubChainRequest(NON_CHAIN_REQUEST_URI);
        stubResponseStatus(HttpServletResponse.SC_NOT_FOUND);

        servlet.service(request, response);

        verify(response, never()).setStatus(any(Integer.class));
        verify(request, never()).getMethod();
        verifyNoInteractions(jsonMapper, writer);
    }

    @Test
    void shouldDoNothingWhenErrorRequestUriIsBlank() throws Exception {
        stubChainRequest(" ");
        stubResponseStatus(HttpServletResponse.SC_NOT_FOUND);

        servlet.service(request, response);

        verify(response, never()).setStatus(any(Integer.class));
        verifyNoInteractions(jsonMapper, writer);
    }

    @Test
    void shouldDoNothingWhenErrorRequestUriIsMissing() throws Exception {
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn(null);
        stubResponseStatus(HttpServletResponse.SC_NOT_FOUND);

        servlet.service(request, response);

        verify(response, never()).setStatus(any(Integer.class));
        verifyNoInteractions(jsonMapper, writer);
    }

    @Test
    void shouldPropagateJsonProcessingExceptionWhenErrorResponseCannotBeSerialized() throws Exception {
        JsonProcessingException exception = new JsonProcessingException("Serialization failed") {
        };

        stubChainRequest(CHAIN_REQUEST_URI);
        stubResponseStatus(HttpServletResponse.SC_NOT_FOUND);

        when(jsonMapper.writeValueAsString(any())).thenThrow(exception);

        JsonProcessingException result = assertThrows(
            JsonProcessingException.class,
            () -> servlet.service(request, response)
        );

        assertSame(exception, result);
        verify(response).setStatus(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND.getHttpErrorCode());
    }

    @Test
    void shouldPropagateIOExceptionWhenResponseWriterCannotBeObtained() throws Exception {
        IOException exception = new IOException("Writer failed");

        stubChainRequest(CHAIN_REQUEST_URI);
        stubResponseStatus(HttpServletResponse.SC_NOT_FOUND);

        when(jsonMapper.writeValueAsString(any())).thenReturn(RESPONSE_BODY);
        when(response.getWriter()).thenThrow(exception);

        IOException result = assertThrows(
            IOException.class,
            () -> servlet.service(request, response)
        );

        assertSame(exception, result);
        verify(response).setStatus(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND.getHttpErrorCode());
    }

    private void stubChainRequest(String requestUri) {
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn(requestUri);
    }

    private void stubResponseStatus(int status) {
        when(response.getStatus()).thenReturn(status);
    }

    private void stubResponseBodyWriting() throws IOException {
        when(jsonMapper.writeValueAsString(any())).thenReturn(RESPONSE_BODY);
        when(response.getWriter()).thenReturn(writer);
    }

    private ErrorEntry captureErrorEntry() throws JsonProcessingException {
        ArgumentCaptor<ErrorEntry> errorEntryCaptor = ArgumentCaptor.forClass(ErrorEntry.class);

        verify(jsonMapper).writeValueAsString(errorEntryCaptor.capture());

        return errorEntryCaptor.getValue();
    }
}
