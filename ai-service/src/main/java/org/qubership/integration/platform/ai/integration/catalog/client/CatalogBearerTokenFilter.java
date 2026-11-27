package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.Optional;
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
    Optional<String> userToken = UserTokenStore.current();
    if (userToken.isPresent()) {
      requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + userToken.get());
      return;
    }
    // An expired user token is never replaced by the service token: it is recorded as a catalog 401.
    if (UserTokenStore.currentStatus() == UserTokenStore.Status.EXPIRED) {
      UserTokenStore.rejectCurrent(401);
      throw new NotAuthorizedException("Bearer");
    }
    if (AuthPolicy.required()) {
      throw new NotAuthorizedException("Bearer");
    }
    if (token != null) {
      requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
  }
}
