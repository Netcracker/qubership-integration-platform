package org.qubership.integration.platform.engine.errorhandling.handlers;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.qubership.integration.platform.engine.errorhandling.LoggingMaskingException;

import static org.qubership.integration.platform.engine.errorhandling.handlers.ExceptionDtoHelper.getExceptionDTO;

@Provider
public class LoggingMaskingExceptionHandler implements ExceptionMapper<LoggingMaskingException> {
    @Override
    public Response toResponse(LoggingMaskingException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(getExceptionDTO(exception)).build();
    }
}
