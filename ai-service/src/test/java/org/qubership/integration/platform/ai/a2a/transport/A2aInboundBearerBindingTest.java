package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.security.UnauthorizedException;
import java.util.HashMap;
import java.util.Map;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.transport.rest.context.RestContextKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

class A2aInboundBearerBindingTest {

  @AfterEach
  void cleanup() {
    UserTokenStore.remove("conv-a2a", A2aTestAuth.BEARER);
    ToolSession.clear();
  }

  @Test
  void readsBearerFromCallContextHeaders() {
    RequestContext context = contextWithAuthorization(A2aTestAuth.AUTHORIZATION_HEADER);

    assertEquals(A2aTestAuth.BEARER, A2aInboundBearerBinding.bearerFrom(context));
  }

  @Test
  void missingBearerIsRejected() {
    RequestContext context = contextWithAuthorization(null);

    assertThrows(UnauthorizedException.class, () -> A2aInboundBearerBinding.requireBearer(context));
  }

  @Test
  void bindMakesTokenVisibleToCatalogFilterSeam() {
    A2aInboundBearerBinding.bind("conv-a2a", A2aTestAuth.BEARER);

    assertEquals(A2aTestAuth.BEARER, UserTokenStore.current().orElseThrow());
  }

  private static RequestContext contextWithAuthorization(String authorization) {
    Map<String, String> headers = new HashMap<>();
    if (authorization != null) {
      headers.put("Authorization", authorization);
    }
    Map<String, Object> state = Map.of(RestContextKeys.HEADERS_KEY, headers);
    ServerCallContext callContext =
        new ServerCallContext(UnauthenticatedUser.INSTANCE, state, java.util.Set.of());
    RequestContext context = mock(RequestContext.class);
    when(context.getCallContext()).thenReturn(callContext);
    return context;
  }
}
