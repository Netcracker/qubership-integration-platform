package org.qubership.integration.platform.engine.errorhandling.handlers;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static org.qubership.integration.platform.engine.errorhandling.handlers.ExceptionDtoHelper.getExceptionDTO;

@Provider
public class GeneralExceptionHandler implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception exception) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(getExceptionDTO(exception)).build();
    }
}
