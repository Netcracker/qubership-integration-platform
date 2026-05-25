package org.qubership.integration.platform.ai.integration.catalog;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogChainTools;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optional live smoke against a running runtime-catalog. Requires {@code CATALOG_IT_URL} (e.g.
 * {@code http://localhost:8080}). Does not start Docker; set {@code CATALOG_TOKEN} if your catalog
 * requires auth.
 */
@EnabledIfEnvironmentVariable(named = CatalogItUrlResource.ENV_CATALOG_IT_URL, matches = ".+")
@QuarkusTest
@QuarkusTestResource(CatalogItUrlResource.class)
class CatalogLiveSmokeIT {

  @Inject CatalogChainTools catalogChainTools;

  @Inject @RestClient CatalogRestClient catalogRestClient;

  @Test
  void createGetDeleteChainRoundTrip() {
    String name = "ai-smoke-" + java.util.UUID.randomUUID();
    String createOut = catalogChainTools.createChain(name, "catalog live smoke");
    assertTrue(createOut.contains("chainId="), createOut);
    String chainId = extractChainId(createOut);
    String getOut = catalogChainTools.getChain(chainId);
    assertTrue(getOut.contains(chainId), getOut);
    catalogRestClient.deleteChain(chainId);
  }

  private static String extractChainId(String toolOutput) {
    int i = toolOutput.indexOf("chainId=");
    if (i < 0) {
      throw new IllegalArgumentException("No chainId in: " + toolOutput);
    }
    String rest = toolOutput.substring(i + "chainId=".length()).trim();
    int space = rest.indexOf(' ');
    return space > 0 ? rest.substring(0, space) : rest;
  }
}
