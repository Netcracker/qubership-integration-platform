package org.qubership.integration.platform.ai.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke-check default observability flags committed in application.properties (LangChain4j off;
 * catalog body logging env default true with profile overrides).
 */
class AiServiceLoggingDefaultsContractTest {

  @Test
  void applicationPropertiesDefaultsQuietLangChainAndCatalogBody() throws Exception {
    Path path = Path.of("src/main/resources/application.properties");
    String text = Files.readString(path);
    assertTrue(text.contains("quarkus.langchain4j.log-requests=false"), text);
    assertTrue(text.contains("quarkus.langchain4j.log-responses=false"), text);
    assertTrue(
        text.contains("qip.ai.catalog.log-response-body=${CATALOG_LOG_RESPONSE_BODY:true}"), text);
  }
}
