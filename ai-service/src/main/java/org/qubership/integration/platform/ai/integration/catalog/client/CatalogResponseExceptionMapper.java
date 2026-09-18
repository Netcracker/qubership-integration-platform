package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;

/** Maps only non-rate-limited client responses to the fault-tolerance abort type. */
public final class CatalogResponseExceptionMapper implements ResponseExceptionMapper<RuntimeException> {

  private static final Logger LOG = Logger.getLogger(CatalogResponseExceptionMapper.class);

  @Override
  public RuntimeException toThrowable(Response response) {
    int status = response.getStatus();
    if (status >= 400 && status < 500 && status != 429) {
      String bodySnippet = CatalogRestSupport.readResponseBodySnippet(response);
      if (bodySnippet != null && !bodySnippet.isBlank()) {
        LOG.warnf("Catalog client error HTTP %d body=%s", status, bodySnippet);
      }
      return new CatalogNonRetryableResponseException(response, bodySnippet);
    }
    return new WebApplicationException(response);
  }
}
