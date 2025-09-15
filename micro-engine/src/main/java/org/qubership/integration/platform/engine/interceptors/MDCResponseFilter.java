package org.qubership.integration.platform.engine.interceptors;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import org.slf4j.MDC;

import java.io.IOException;

public class MDCResponseFilter implements ContainerResponseFilter {
    @Override
    public void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext
    ) throws IOException {
        MDC.clear();
    }
}
