package org.qubership.integration.platform.ai.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class RestClientTimeoutContractTest {

  @Test
  void apiHubClientDeclaresTenSecondTimeout() {
    Timeout timeout = ApiHubMcpClient.class.getAnnotation(Timeout.class);
    assertNotNull(timeout);
    assertEquals(10, timeout.value());
    assertEquals(ChronoUnit.SECONDS, timeout.unit());
  }

  @Test
  void catalogClientKeepsTenSecondTimeout() {
    Timeout timeout = CatalogRestClient.class.getAnnotation(Timeout.class);
    assertNotNull(timeout);
    assertEquals(10, timeout.value());
    assertEquals(ChronoUnit.SECONDS, timeout.unit());
  }

  @Test
  void applicationPropertiesSetConnectAndReadTimeouts() throws IOException {
    String properties =
        new String(
            RestClientTimeoutContractTest.class
                .getClassLoader()
                .getResourceAsStream("application.properties")
                .readAllBytes(),
            StandardCharsets.UTF_8);
    assertTrue(properties.contains("quarkus.rest-client.apihub-mcp.connect-timeout="));
    assertTrue(properties.contains("quarkus.rest-client.apihub-mcp.read-timeout="));
    assertTrue(properties.contains("quarkus.rest-client.catalog-api.connect-timeout="));
    assertTrue(properties.contains("quarkus.rest-client.catalog-api.read-timeout="));
  }
}
