package org.qubership.integration.platform.engine.interceptors;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.qubership.integration.platform.engine.logging.constants.ContextHeaders;
import org.slf4j.MDC;

import java.io.IOException;

@WebFilter(urlPatterns = {"/*"}, dispatcherTypes = {DispatcherType.REQUEST})
public class MDCServletFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpServletRequest) {
            String value = httpServletRequest.getHeader(ContextHeaders.REQUEST_ID_HEADER);
            RequestIdHelper.setRequestId(value);
        }
        chain.doFilter(request, response);
        MDC.clear();
    }
}
