package org.qubership.integration.platform.ai.integration.catalog.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

@ApplicationScoped
public class CatalogAuthorizationHeadersFactory implements ClientHeadersFactory {

  @Override
  public MultivaluedMap<String, String> update(
      MultivaluedMap<String, String> incomingHeaders,
      MultivaluedMap<String, String> clientOutgoingHeaders) {
    String authorization =
        CatalogAuthorizationContext.resolveAuthorization()
            .orElseThrow(CatalogAuthorizationMissingException::new);
    clientOutgoingHeaders.putSingle(HttpHeaders.AUTHORIZATION, authorization);
    return clientOutgoingHeaders;
  }
}
