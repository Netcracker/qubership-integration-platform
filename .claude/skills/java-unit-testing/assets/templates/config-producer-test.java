package org.qubership.integration.platform.engine.interceptors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.logging.ContextHeaders;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.slf4j.MDC;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MDCServletFilterTest {

    private static final String REQUEST_ID = "687331fd-9a44-409c-b580-11cacd5375e5";

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldSetRequestIdFromHttpRequestHeaderAndContinueFilterChain() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        MDCServletFilter filter = new MDCServletFilter();

        when(request.getHeader(ContextHeaders.REQUEST_ID_HEADER)).thenReturn(REQUEST_ID);

        try (MockedStatic<RequestIdHelper> requestIdHelper = Mockito.mockStatic(RequestIdHelper.class)) {
            filter.doFilter(request, response, chain);

            requestIdHelper.verify(() -> RequestIdHelper.setRequestId(REQUEST_ID));
            verify(chain).doFilter(request, response);
        }
    }

    @Test
    void shouldSetNullRequestIdWhenHttpRequestHeaderIsMissing() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        MDCServletFilter filter = new MDCServletFilter();

        when(request.getHeader(ContextHeaders.REQUEST_ID_HEADER)).thenReturn(null);

        try (MockedStatic<RequestIdHelper> requestIdHelper = Mockito.mockStatic(RequestIdHelper.class)) {
            filter.doFilter(request, response, chain);

            requestIdHelper.verify(() -> RequestIdHelper.setRequestId(null));
            verify(chain).doFilter(request, response);
        }
    }

    @Test
    void shouldNotSetRequestIdWhenRequestIsNotHttpServletRequest() throws Exception {
        ServletRequest request = mock(ServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        MDCServletFilter filter = new MDCServletFilter();

        try (MockedStatic<RequestIdHelper> requestIdHelper = Mockito.mockStatic(RequestIdHelper.class)) {
            filter.doFilter(request, response, chain);

            requestIdHelper.verifyNoInteractions();
            verify(chain).doFilter(request, response);
        }
    }

    @Test
    void shouldClearMdcAfterSuccessfulFilterChainExecution() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        MDCServletFilter filter = new MDCServletFilter();

        when(request.getHeader(ContextHeaders.REQUEST_ID_HEADER)).thenReturn(REQUEST_ID);

        filter.doFilter(request, response, chain);

        assertNull(MDC.get(ContextHeaders.REQUEST_ID));
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldPropagateIOExceptionWhenFilterChainFails() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        MDCServletFilter filter = new MDCServletFilter();
        IOException exception = new IOException("Filter chain failed");

        when(request.getHeader(ContextHeaders.REQUEST_ID_HEADER)).thenReturn(REQUEST_ID);
        Mockito.doThrow(exception).when(chain).doFilter(request, response);

        IOException result = assertThrows(
            IOException.class,
            () -> filter.doFilter(request, response, chain)
        );

        assertSame(exception, result);
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldPropagateServletExceptionWhenFilterChainFails() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        MDCServletFilter filter = new MDCServletFilter();
        ServletException exception = new ServletException("Filter chain failed");

        when(request.getHeader(ContextHeaders.REQUEST_ID_HEADER)).thenReturn(REQUEST_ID);
        Mockito.doThrow(exception).when(chain).doFilter(request, response);

        ServletException result = assertThrows(
            ServletException.class,
            () -> filter.doFilter(request, response, chain)
        );

        assertSame(exception, result);
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldNotClearMdcWhenFilterChainFailsWithCurrentImplementation() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        MDCServletFilter filter = new MDCServletFilter();
        IOException exception = new IOException("Filter chain failed");

        when(request.getHeader(ContextHeaders.REQUEST_ID_HEADER)).thenReturn(REQUEST_ID);
        Mockito.doThrow(exception).when(chain).doFilter(request, response);

        try (MockedStatic<MDC> mdc = Mockito.mockStatic(MDC.class)) {
            assertThrows(IOException.class, () -> filter.doFilter(request, response, chain));

            mdc.verify(MDC::clear, never());
        }
    }
}
