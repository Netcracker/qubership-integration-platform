package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.ConfigProvider;
import org.qubership.integration.platform.ai.chat.AuthPolicy;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;


public class CatalogBearerTokenFilter implements ClientRequestFilter {

  private static final String CFG_TOKEN = "qip.ai.catalog.token";

  private final String token;

  public CatalogBearerTokenFilter() {
    this(ConfigProvider.getConfig().getOptionalValue(CFG_TOKEN, String.class).orElse(null));
  }

  CatalogBearerTokenFilter(String token) {
    this.token = token == null || token.isBlank() ? null : token.trim();
  }

  @Override
  public void filter(ClientRequestContext requestContext) {
    if (requestContext.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
      return;
    }
    // The renewal wait happens in AwaitUsableTurnBearerInterceptor, before the timed HTTP call.
    if (AuthPolicy.required()) {
      requestContext
          .getHeaders()
          .putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + usableTurnBearer());
      return;
    }
    // Auth off: a turn that carries a bearer still uses it; otherwise the service identity serves
    // the local stack.
    if (ToolSession.resolveConversationId() != null && UserTokenStore.current().isPresent()) {
      requestContext
          .getHeaders()
          .putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + usableTurnBearer());
      return;
    }
    if (token == null) {
      return;
    }
    requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
  }

  private static String usableTurnBearer() {
    return UserTokenStore.currentUsable(0).orElseThrow(() -> new NotAuthorizedException("Bearer"));
  }
}
