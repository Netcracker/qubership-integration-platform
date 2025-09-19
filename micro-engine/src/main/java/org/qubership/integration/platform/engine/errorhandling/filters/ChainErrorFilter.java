package org.qubership.integration.platform.engine.errorhandling.filters;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCodeException;

import java.io.IOException;

@Provider
public class ChainErrorFilter implements ContainerResponseFilter {
    @ConfigProperty(name = "qip.camel.routes-prefix")
    String camelRoutesPrefix;

    @Override
    public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) throws IOException {
        if (isErrorResponse(containerResponseContext) && isChainRequest(containerRequestContext)) {
            ErrorCodeException error = getChainErrorStatus(containerRequestContext, containerResponseContext);
            containerResponseContext.setStatus(error.getErrorCode().getHttpErrorCode());
            containerResponseContext.setEntity(error.buildResponseObject());
        }
    }

    private boolean isChainRequest(ContainerRequestContext containerRequestContext) {
        String path = containerRequestContext.getUriInfo().getPath();
        return !StringUtils.isBlank(path) && path.startsWith(camelRoutesPrefix);
    }

    private boolean isErrorResponse(ContainerResponseContext containerResponseContext) {
        return containerResponseContext.getStatus() >= Response.Status.BAD_REQUEST.getStatusCode();
    }

    private ErrorCodeException getChainErrorStatus(
            ContainerRequestContext containerRequestContext,
            ContainerResponseContext containerResponseContext
    ) {
        int status = containerResponseContext.getStatus();
        String method = containerRequestContext.getMethod();
        return switch (status) {
            case 405 -> new ErrorCodeException(ErrorCode.METHOD_NOT_ALLOWED, method);
            case 404 -> new ErrorCodeException(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND);
            default -> new ErrorCodeException(ErrorCode.UNEXPECTED_BUSINESS_ERROR);
        };
    }
}
