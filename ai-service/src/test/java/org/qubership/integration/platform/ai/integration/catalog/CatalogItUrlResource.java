package org.qubership.integration.platform.ai.integration.catalog;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/** Points the catalog REST client at {@code CATALOG_IT_URL} for optional live integration tests. */
public class CatalogItUrlResource implements QuarkusTestResourceLifecycleManager {

  static final String ENV_CATALOG_IT_URL = "CATALOG_IT_URL";

  @Override
  public Map<String, String> start() {
    String url = System.getenv(ENV_CATALOG_IT_URL);
    if (url == null || url.isBlank()) {
      return Map.of();
    }
    return Map.of(
        "quarkus.rest-client.catalog-api.url", url,
        "qip.ai.catalog.base-url", url);
  }

  @Override
  public void stop() {
      // External catalog has no test-scoped process to stop.
  }
}
