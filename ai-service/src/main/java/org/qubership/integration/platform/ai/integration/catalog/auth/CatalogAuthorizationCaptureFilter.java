package org.qubership.integration.platform.ai.integration.catalog.auth;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

/** Captures inbound {@code Authorization} for chat and harness endpoints. */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class CatalogAuthorizationCaptureFilter implements ContainerRequestFilter {

  @Inject CatalogAuthorizationCapture capture;

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!shouldCapture(requestContext.getUriInfo().getPath())) {
      return;
    }
    capture.setAuthorization(requestContext.getHeaderString("Authorization"));
  }

  static boolean shouldCapture(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    String normalized = path.startsWith("/") ? path : "/" + path;
    if (normalized.startsWith("/api/v1/chat")) {
      return true;
    }
    if (normalized.startsWith("/api/ui/v1/chat")) {
      return true;
    }
    return normalized.startsWith("/api/v1/harness/");
  }
}
