package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.ConfigProvider;
import org.qubership.integration.platform.ai.chat.AuthPolicy;
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
    String bearer = UserTokenStore.current().orElse(null);
    if (bearer == null && AuthPolicy.required()) {
      throw new NotAuthorizedException("Bearer");
    }
    if (bearer == null) {
      bearer = token;
    }
    if (bearer != null) {
      requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
  }
}
