package org.qubership.integration.platform.ai.integration.catalog.auth;

import java.util.Optional;

/** Thread-local holder for inbound Authorization on non-JAX-RS entry points (for example A2A /rpc). */
public final class InboundCatalogAuthorization {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private InboundCatalogAuthorization() {}

  public static void set(String authorizationHeader) {
    CatalogAuthorizationSupport.normalizeAuthorization(authorizationHeader).ifPresent(CURRENT::set);
  }

  public static Optional<String> current() {
    String authorization = CURRENT.get();
    if (authorization == null || authorization.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(authorization);
  }

  public static void clear() {
    CURRENT.remove();
  }
}
