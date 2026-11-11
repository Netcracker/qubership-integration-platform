package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

class CatalogBearerTokenFilterTest {

  @Test
  void addsBearerHeaderWhenTokenConfigured() {
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    new CatalogBearerTokenFilter("secret").filter(request(headers));

    assertEquals("Bearer secret", headers.getFirst(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void omitsHeaderWhenTokenBlank() {
    for (String token : new String[] {null, "", "  "}) {
      MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

      new CatalogBearerTokenFilter(token).filter(request(headers));

      assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
    }
  }

  @Test
  void keepsAuthorizationSetByAnotherProvider() {
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer m2m");

    new CatalogBearerTokenFilter("secret").filter(request(headers));

    assertEquals("Bearer m2m", headers.getFirst(HttpHeaders.AUTHORIZATION));
  }

  private static ClientRequestContext request(MultivaluedMap<String, Object> headers) {
    ClientRequestContext context = mock(ClientRequestContext.class);
    when(context.getHeaders()).thenReturn(headers);
    return context;
  }
}
