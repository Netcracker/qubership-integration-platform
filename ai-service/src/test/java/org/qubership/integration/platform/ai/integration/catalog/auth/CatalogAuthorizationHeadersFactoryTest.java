package org.qubership.integration.platform.ai.integration.catalog.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CatalogAuthorizationHeadersFactoryTest {

  private final CatalogAuthorizationHeadersFactory factory = new CatalogAuthorizationHeadersFactory();

  @AfterEach
  void clearState() {
    CatalogAuthorizationContext.clear("conv-headers");
  }

  @Test
  void setsOutboundAuthorizationFromContext() {
    CatalogAuthorizationContext.bind("conv-headers", "Bearer outbound-token");
    MultivaluedHashMap<String, String> outgoing = new MultivaluedHashMap<>();
    factory.update(new MultivaluedHashMap<>(), outgoing);
    assertEquals("Bearer outbound-token", outgoing.getFirst(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void missingAuthorizationFailsFast() {
    MultivaluedHashMap<String, String> outgoing = new MultivaluedHashMap<>();
    assertThrows(CatalogAuthorizationMissingException.class, () -> factory.update(new MultivaluedHashMap<>(), outgoing));
  }
}
