package org.qubership.integration.platform.engine.errorhandling.handlers;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.qubership.integration.platform.engine.errorhandling.ChainNotDeployedOnEngineException;

import static org.qubership.integration.platform.engine.errorhandling.handlers.ExceptionDtoHelper.getExceptionDTO;

@Provider
public class ChainNotDeployedOnEngineExceptionHandler
        implements ExceptionMapper<ChainNotDeployedOnEngineException> {
    @Override
    public Response toResponse(ChainNotDeployedOnEngineException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(getExceptionDTO(exception, false))
                .build();
    }
}
