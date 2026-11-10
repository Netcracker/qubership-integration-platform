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
  /** Negative means use {@link UserTokenStore#currentUsable()} default wait. */
  private final long renewWaitMillis;

  public CatalogBearerTokenFilter() {
    this(ConfigProvider.getConfig().getOptionalValue(CFG_TOKEN, String.class).orElse(null));
  }

  CatalogBearerTokenFilter(String token) {
    this(token, -1L);
  }

  CatalogBearerTokenFilter(String token, long renewWaitMillis) {
    this.token = token == null || token.isBlank() ? null : token.trim();
    this.renewWaitMillis = renewWaitMillis;
  }

  @Override
  public void filter(ClientRequestContext requestContext) {
    if (requestContext.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
      return;
    }
    // Inside a turn: caller's usable token only — never fall back to the service identity.
    // Exception: auth is switched off (local stack) and the turn carries no token at all.
    if (ToolSession.resolveConversationId() != null
        && (AuthPolicy.required() || UserTokenStore.current().isPresent())) {
      String user =
          (renewWaitMillis < 0
                  ? UserTokenStore.currentUsable()
                  : UserTokenStore.currentUsable(renewWaitMillis))
              .orElseThrow(() -> new NotAuthorizedException("Bearer"));
      requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + user);
      return;
    }
    if (token == null) {
      return;
    }
    requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
  }
}
