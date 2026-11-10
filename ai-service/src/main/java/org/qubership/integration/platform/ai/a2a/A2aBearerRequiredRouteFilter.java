package org.qubership.integration.platform.ai.a2a;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.AuthPolicy;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

/**
 * Requires {@code Authorization: Bearer} on A2A invocation routes before the SDK handles the
 * request, so missing caller credentials return HTTP 401 instead of running catalog work.
 */
@ApplicationScoped
public class A2aBearerRequiredRouteFilter {

  private final A2aFeatureGate featureGate;

  @Inject
  public A2aBearerRequiredRouteFilter(A2aFeatureGate featureGate) {
    this.featureGate = featureGate;
  }

  void register(@Observes Router router) {
    router
        .route()
        .order(-1999)
        .handler(
            ctx -> {
              if (!featureGate.enabled() || !AuthPolicy.required()) {
                ctx.next();
                return;
              }
              String path = ctx.request().path();
              if (path == null || !requiresCallerBearer(path)) {
                ctx.next();
                return;
              }
              if (UserTokenStore.parseBearer(ctx.request().getHeader("Authorization")) == null) {
                ctx
                    .response()
                    .setStatusCode(401)
                    .putHeader("WWW-Authenticate", "Bearer")
                    .end();
                return;
              }
              ctx.next();
            });
  }

  static boolean requiresCallerBearer(String path) {
    if ("/.well-known/agent-card.json".equals(path)
        || path.endsWith("/.well-known/agent-card.json")) {
      return false;
    }
    if ("/rpc".equals(path) || path.endsWith("/rpc")) {
      return true;
    }
    return A2aDisabledRouteFilter.isA2aPath(path);
  }
}
