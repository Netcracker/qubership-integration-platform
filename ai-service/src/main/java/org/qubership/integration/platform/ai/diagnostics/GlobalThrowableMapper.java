package org.qubership.integration.platform.ai.diagnostics;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs every unhandled exception at ERROR with full stack trace (server logs). Intended for local
 * debugging when reactive chains or SSE hide causes of HTTP 500.
 */
@Provider
public class GlobalThrowableMapper implements ExceptionMapper<Throwable> {

  private static final Logger LOG = Logger.getLogger(GlobalThrowableMapper.class);

  @Override
  public Response toResponse(Throwable exception) {
    if (exception instanceof WebApplicationException wae) {
      Response r = wae.getResponse();
      if (r.getStatus() >= 500) {
        LOG.error("WebApplicationException with HTTP " + r.getStatus(), exception);
      }
      return r;
    }

    LOG.error("Unhandled REST exception — see stack trace below", exception);

    Map<String, Object> body = LinkedHashMap.newLinkedHashMap(4);
    body.put(
        "error",
        exception.getMessage() != null
            ? exception.getMessage()
            : exception.getClass().getSimpleName());
    body.put("exceptionType", exception.getClass().getName());

    return Response.serverError().type(MediaType.APPLICATION_JSON).entity(body).build();
  }
}
