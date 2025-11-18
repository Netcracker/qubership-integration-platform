package org.qubership.integration.platform.engine.errorhandling.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCodeException;

import java.io.IOException;

@Slf4j
@WebServlet(urlPatterns = "/error")
public class ChainErrorServlet extends HttpServlet {
    @ConfigProperty(name = "qip.camel.routes-prefix")
    String camelRoutesPrefix;

    @Inject
    @Identifier("jsonMapper")
    ObjectMapper jsonMapper;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (isErrorResponse(response) && isChainRequest(request)) {
            ErrorCodeException error = getChainErrorStatus(request, response);
            response.setStatus(error.getErrorCode().getHttpErrorCode());
            String body = jsonMapper.writeValueAsString(error.buildResponseObject());
            response.getWriter().print(body);
        }
    }

    private boolean isChainRequest(HttpServletRequest request) {
        String path = String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        return !StringUtils.isBlank(path) && path.startsWith(camelRoutesPrefix);
    }

    private boolean isErrorResponse(HttpServletResponse response) {
        return response.getStatus() >= Response.Status.BAD_REQUEST.getStatusCode();
    }

    private ErrorCodeException getChainErrorStatus(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        int status = response.getStatus();
        String method = request.getMethod();
        return switch (status) {
            case 405 -> new ErrorCodeException(ErrorCode.METHOD_NOT_ALLOWED, method);
            case 404 -> new ErrorCodeException(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND);
            default -> new ErrorCodeException(ErrorCode.UNEXPECTED_BUSINESS_ERROR);
        };
    }
}
