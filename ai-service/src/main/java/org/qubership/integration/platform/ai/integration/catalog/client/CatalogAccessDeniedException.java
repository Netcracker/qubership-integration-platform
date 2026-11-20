package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.core.Response;

/**
 * Catalog answered 401 or 403 for the caller's token. The denial belongs to one caller, so the
 * shared circuit breaker skips it instead of counting it as a Catalog health failure.
 */
public final class CatalogAccessDeniedException extends CatalogNonRetryableResponseException {

  public CatalogAccessDeniedException(Response response, String bodySnippet) {
    super(response, bodySnippet);
  }
}
