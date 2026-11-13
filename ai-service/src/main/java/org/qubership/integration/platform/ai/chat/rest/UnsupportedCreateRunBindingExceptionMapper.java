package org.qubership.integration.platform.ai.chat.rest;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.qubership.integration.platform.ai.productpipeline.create.UnsupportedCreateRunBindingException;

@Provider
public class UnsupportedCreateRunBindingExceptionMapper
    implements ExceptionMapper<UnsupportedCreateRunBindingException> {
  @Override
  public Response toResponse(UnsupportedCreateRunBindingException exception) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.TEXT_PLAIN_TYPE)
        .entity(exception.sseMessage())
        .build();
  }
}
