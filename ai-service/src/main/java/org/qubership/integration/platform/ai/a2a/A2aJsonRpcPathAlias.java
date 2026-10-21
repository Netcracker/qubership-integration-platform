package org.qubership.integration.platform.ai.a2a;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.a2aproject.sdk.compat03.server.apps.quarkus.A2AServerRoutes_v0_3;
import org.a2aproject.sdk.server.apps.quarkus.A2AServerRoutes;
import org.a2aproject.sdk.server.common.quarkus.VersionRouter;
import org.a2aproject.sdk.server.common.quarkus.VertxSecurityHelper;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Mirrors the SDK JSON-RPC handler at {@code POST /rpc}.
 *
 * <p>The A2A Java SDK registers JSON-RPC at {@code POST /}. Netcracker Agentic AI Platform and DCA
 * advertise {@code /rpc}, and the Agent Card points there, so the same dispatch runs on both paths
 * without forking the SDK.
 *
 * <p>Version selection follows {@code MultiVersionJSONRPCRoutes}: the {@code A2A-Version} header
 * picks the dialect, and its absence means {@code 0.3}. That default matters — a Python {@code
 * a2a-sdk} client sends no header and calls {@code message/send}, which only the 0.3 handler
 * accepts; the 1.0 handler expects {@code SendMessage}.
 */
@ApplicationScoped
public class A2aJsonRpcPathAlias {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(A2aJsonRpcPathAlias.class);

  /** Enough for a create-chain request with a pasted specification, short of filling the log. */
  private static final int MAX_LOGGED_PAYLOAD_CHARS = 8192;

  private final Instance<A2AServerRoutes> jsonRpcRoutes;
  private final Instance<A2AServerRoutes_v0_3> legacyJsonRpcRoutes;
  private final VertxSecurityHelper vertxSecurityHelper;
  private final boolean logInboundPayload;

  @Inject
  public A2aJsonRpcPathAlias(
      Instance<A2AServerRoutes> jsonRpcRoutes,
      Instance<A2AServerRoutes_v0_3> legacyJsonRpcRoutes,
      VertxSecurityHelper vertxSecurityHelper,
      AppConfig appConfig) {
    this.jsonRpcRoutes = jsonRpcRoutes;
    this.legacyJsonRpcRoutes = legacyJsonRpcRoutes;
    this.vertxSecurityHelper = vertxSecurityHelper;
    this.logInboundPayload = appConfig.a2a().logInboundPayload();
  }

  void setupRoutes(@Observes Router router) {
    if (!jsonRpcRoutes.isResolvable() || !legacyJsonRpcRoutes.isResolvable()) {
      return;
    }
    router
        .post(A2aProtocolConstants.JSONRPC_PATH)
        .consumes(APPLICATION_JSON)
        .handler(BodyHandler.create())
        .blockingHandler(this::dispatch, false);
  }

  private void dispatch(RoutingContext ctx) {
    try {
      vertxSecurityHelper.runInRequestContextDeferred(ctx, () -> invokeForVersion(ctx));
    } catch (UnauthorizedException | ForbiddenException e) {
      vertxSecurityHelper.handleAuthError(ctx, e);
    } catch (Exception e) {
      VertxSecurityHelper.handleGenericError(ctx);
    }
  }

  private void invokeForVersion(RoutingContext ctx) {
    String version = VersionRouter.resolveVersion(ctx);
    String body = ctx.body().asString();
    logInboundPayload(version, body);
    if (VersionRouter.isV10(version)) {
      jsonRpcRoutes.get().invokeJSONRPCHandler(body, ctx);
    } else {
      // ponytail: an unrecognised version falls through to 0.3 and answers "method not found"
      // instead of a typed VersionNotSupportedError. Reject explicitly once a peer sends one.
      legacyJsonRpcRoutes.get().invokeJSONRPCHandler(body, ctx);
    }
  }

  /**
   * Records the request exactly as it arrived, before any deserialization.
   *
   * <p>Every other log in this service reports fields the service already understood, which cannot
   * show a value the caller put somewhere unexpected: a field named differently, nested one level
   * off, or dropped during binding all read back as absent. This is the only place the wire form
   * survives.
   *
   * <p>Off by default. The body carries the caller's own text, which the launch observability rules
   * keep out of logs, so this is a deliberate debugging window rather than steady-state telemetry.
   */
  private void logInboundPayload(String version, String body) {
    if (!logInboundPayload || !LOG.isInfoEnabled()) {
      return;
    }
    String captured =
        body == null
            ? "<empty>"
            : body.length() > MAX_LOGGED_PAYLOAD_CHARS
                ? body.substring(0, MAX_LOGGED_PAYLOAD_CHARS) + "…<truncated>"
                : body;
    LOG.infof("A2A inbound payload version=%s body=%s", version, captured);
  }
}
