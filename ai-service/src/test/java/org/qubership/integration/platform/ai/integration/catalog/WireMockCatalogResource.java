package org.qubership.integration.platform.ai.integration.catalog;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * In-process HTTP mock for contract tests; sets {@code quarkus.rest-client.catalog-api.url} to the
 * mock base URL.
 */
public class WireMockCatalogResource implements QuarkusTestResourceLifecycleManager {

  public static volatile WireMockServer SERVER;

  @Override
  public Map<String, String> start() {
    WireMockServer server =
        new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    server.start();
    SERVER = server;
    String base = server.baseUrl();
    return Map.of(
        "quarkus.rest-client.catalog-api.url", base,
        "qip.ai.catalog.base-url", base);
  }

  @Override
  public void stop() {
    if (SERVER != null) {
      SERVER.stop();
      SERVER = null;
    }
  }
}
