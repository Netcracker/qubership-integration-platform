package org.qubership.integration.platform.engine.errorhandling.handlers;

import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static org.qubership.integration.platform.engine.errorhandling.handlers.ExceptionDtoHelper.getExceptionDTO;

@Provider
public class EntityNotFoundExceptionHandler implements ExceptionMapper<EntityNotFoundException> {
    @Override
    public Response toResponse(EntityNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(getExceptionDTO(exception, false))
                .build();
    }
}
