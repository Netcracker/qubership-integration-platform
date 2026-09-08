package org.qubership.integration.platform.ai.integration.catalog.auth;

import jakarta.enterprise.context.RequestScoped;
import java.util.Optional;

/** Request-scoped holder for the inbound catalog authorization header captured at ingress. */
@RequestScoped
public class CatalogAuthorizationCapture {

  private String authorization;

  public void setAuthorization(String authorization) {
    this.authorization =
        CatalogAuthorizationSupport.normalizeAuthorization(authorization).orElse(null);
  }

  public Optional<String> authorization() {
    return Optional.ofNullable(authorization);
  }
}
