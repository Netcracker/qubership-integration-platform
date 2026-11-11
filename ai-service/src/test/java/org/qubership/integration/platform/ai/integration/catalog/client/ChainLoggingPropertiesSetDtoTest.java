package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainLoggingPropertiesDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainLoggingPropertiesSetDto;

class ChainLoggingPropertiesSetDtoTest {

  @Test
  void customWinsThenConsulThenFallbackThenCatalogDefault() {
    ChainLoggingPropertiesDto custom = properties("DEBUG", "INFO");
    ChainLoggingPropertiesDto consul = properties("INFO", "WARN");
    ChainLoggingPropertiesDto fallback = properties("ERROR", "ERROR");

    assertEquals(
        "DEBUG",
        new ChainLoggingPropertiesSetDto(fallback, consul, custom).effective().sessionsLoggingLevel());
    assertEquals(
        "INFO",
        new ChainLoggingPropertiesSetDto(fallback, consul, null).effective().sessionsLoggingLevel());
    assertEquals(
        "ERROR",
        new ChainLoggingPropertiesSetDto(fallback, null, null).effective().sessionsLoggingLevel());
    assertEquals(
        "OFF", new ChainLoggingPropertiesSetDto(null, null, null).effective().sessionsLoggingLevel());
  }

  @Test
  void withSessionLevelCopiesNonSessionFields() {
    ChainLoggingPropertiesDto current =
        new ChainLoggingPropertiesDto(
            "OFF", "WARN", List.of("BODY", "HEADERS"), true, false);

    ChainLoggingPropertiesDto posted = current.withSessionLevel("INFO");

    assertEquals("INFO", posted.sessionsLoggingLevel());
    assertEquals("WARN", posted.logLoggingLevel());
    assertEquals(List.of("BODY", "HEADERS"), posted.logPayload());
    assertEquals(true, posted.dptEventsEnabled());
    assertEquals(false, posted.maskingEnabled());
  }

  private static ChainLoggingPropertiesDto properties(String session, String log) {
    return new ChainLoggingPropertiesDto(session, log, List.of("HEADERS"), false, true);
  }
}
