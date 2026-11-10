package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.transport.A2aInboundBearerBinding;
import org.qubership.integration.platform.ai.a2a.transport.A2aTestAuth;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

/** Catalog seam: A2A-bound turns send the caller token, not the service identity. */
class CatalogBearerTokenFilterA2aTurnTest {

  @AfterEach
  void cleanup() {
    A2aInboundBearerBinding.unbind("task-a2a", A2aTestAuth.BEARER);
  }

  @Test
  void a2aBoundTurnUsesCallerTokenOnCatalogRequest() {
    A2aInboundBearerBinding.bind("task-a2a", A2aTestAuth.BEARER);
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    new CatalogBearerTokenFilter("service-secret").filter(request(headers));

    assertEquals("Bearer " + A2aTestAuth.BEARER, headers.getFirst(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void a2aBoundTurnDoesNotFallBackToServiceToken() {
    ToolSession.bind("task-a2a");
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    assertThrows(
        NotAuthorizedException.class,
        () -> new CatalogBearerTokenFilter("service-secret").filter(request(headers)));
  }

  private static ClientRequestContext request(MultivaluedMap<String, Object> headers) {
    ClientRequestContext context = org.mockito.Mockito.mock(ClientRequestContext.class);
    org.mockito.Mockito.when(context.getHeaders()).thenReturn(headers);
    return context;
  }
}
