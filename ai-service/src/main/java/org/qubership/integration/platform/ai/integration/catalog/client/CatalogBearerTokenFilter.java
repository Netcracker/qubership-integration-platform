package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.ConfigProvider;


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
    if (token == null || requestContext.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
      return;
    }
    requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
  }
}
