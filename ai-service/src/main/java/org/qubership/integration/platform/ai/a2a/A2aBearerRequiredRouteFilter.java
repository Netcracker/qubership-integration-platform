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
              String method = ctx.request().method() == null ? null : ctx.request().method().name();
              String contentType = ctx.request().getHeader("Content-Type");
              if (path == null || !requiresCallerBearer(method, path, contentType)) {
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
    return requiresCallerBearer(null, path, null);
  }

  /**
   * The A2A SDK also accepts JSON-RPC on {@code POST /}. Only a JSON POST to the root is an
   * invocation; the agent card, GETs, and other root requests stay open.
   */
  static boolean requiresCallerBearer(String method, String path, String contentType) {
    if ("/.well-known/agent-card.json".equals(path)
        || path.endsWith("/.well-known/agent-card.json")) {
      return false;
    }
    if ("/rpc".equals(path) || path.endsWith("/rpc")) {
      return true;
    }
    if (isRootJsonRpc(method, path, contentType)) {
      return true;
    }
    return A2aDisabledRouteFilter.isA2aPath(path);
  }

  private static boolean isRootJsonRpc(String method, String path, String contentType) {
    return "/".equals(path)
        && "POST".equalsIgnoreCase(method)
        && contentType != null
        && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
  }
}
