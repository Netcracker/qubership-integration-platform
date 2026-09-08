package org.qubership.integration.platform.ai.integration.catalog.auth;

/** Raised when a catalog outbound call has no user authorization in {@link CatalogAuthorizationContext}. */
public final class CatalogAuthorizationMissingException extends RuntimeException {

  public static final String USER_MESSAGE =
      "No authorization token was provided. Refresh the page and sign in again.";

  public CatalogAuthorizationMissingException() {
    super("Catalog authorization is missing for the active conversation");
  }
}
