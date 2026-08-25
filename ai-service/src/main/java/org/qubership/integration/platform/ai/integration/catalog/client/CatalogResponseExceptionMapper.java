package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/** Maps only non-rate-limited client responses to the fault-tolerance abort type. */
public final class CatalogResponseExceptionMapper implements ResponseExceptionMapper<RuntimeException> {

  @Override
  public RuntimeException toThrowable(Response response) {
    int status = response.getStatus();
    if (status >= 400 && status < 500 && status != 429) {
      return new CatalogNonRetryableResponseException(response);
    }
    return new WebApplicationException(response);
  }
}
