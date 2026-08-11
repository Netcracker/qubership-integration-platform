package org.qubership.integration.platform.ai.a2a;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Maps feature-disabled exceptions to a deliberate HTTP disabled response. */
@Provider
public class A2aFeatureDisabledExceptionMapper
    implements ExceptionMapper<A2aFeatureDisabledException> {

  @Override
  public Response toResponse(A2aFeatureDisabledException exception) {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .type(MediaType.TEXT_PLAIN_TYPE)
        .entity(
            exception.getMessage() == null
                ? A2aFeatureGate.DISABLED_MESSAGE
                : exception.getMessage())
        .build();
  }
}
