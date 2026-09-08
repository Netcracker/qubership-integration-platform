package org.qubership.integration.platform.ai.integration.catalog.auth;

import java.util.Optional;

/** Normalizes inbound {@code Authorization} headers for catalog outbound calls. */
public final class CatalogAuthorizationSupport {

  private static final String BEARER_PREFIX = "Bearer ";

  private CatalogAuthorizationSupport() {}

  public static Optional<String> normalizeAuthorization(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.isBlank()) {
      return Optional.empty();
    }
    String trimmed = authorizationHeader.trim();
    if (trimmed.length() <= BEARER_PREFIX.length()
        || !trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return Optional.empty();
    }
    String token = trimmed.substring(BEARER_PREFIX.length()).trim();
    if (token.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(BEARER_PREFIX + token);
  }
}
