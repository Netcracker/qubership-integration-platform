package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/** A catalog client-response failure that must not be retried. */
public final class CatalogNonRetryableResponseException extends WebApplicationException {

  public CatalogNonRetryableResponseException(Response response) {
    super(response);
  }
}
