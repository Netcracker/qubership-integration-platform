package org.qubership.integration.platform.ai.a2a;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Blocks A2A Vert.x routes when {@code qip.ai.a2a.enabled=false}.
 *
 * <p>The A2A SDK registers discovery and Task routes on the Vert.x router, so a JAX-RS request
 * filter cannot gate them. Browser chat paths are left alone.
 */
@ApplicationScoped
public class A2aDisabledRouteFilter {

  private final A2aFeatureGate featureGate;

  @Inject
  public A2aDisabledRouteFilter(A2aFeatureGate featureGate) {
    this.featureGate = featureGate;
  }

  void register(@Observes Router router) {
    // Run before A2A SDK handlers so disabled discovery/invocation fail closed.
    router
        .route()
        .order(-2000)
        .handler(
            context -> {
              if (featureGate.enabled()) {
                context.next();
                return;
              }
              String path = context.request().path();
              if (path == null || !isA2aPath(path)) {
                context.next();
                return;
              }
              context
                  .response()
                  .setStatusCode(503)
                  .putHeader("Content-Type", "text/plain;charset=UTF-8")
                  .end(A2aFeatureGate.DISABLED_MESSAGE);
            });
  }

  static boolean isA2aPath(String path) {
    if ("/.well-known/agent-card.json".equals(path)
        || path.endsWith("/.well-known/agent-card.json")) {
      return true;
    }
    if (path.equals("/message:send")
        || path.equals("/message:stream")
        || path.endsWith("/message:send")
        || path.endsWith("/message:stream")) {
      return true;
    }
    if (path.startsWith("/tasks/") || path.contains("/tasks/")) {
      return true;
    }
    return false;
  }
}
