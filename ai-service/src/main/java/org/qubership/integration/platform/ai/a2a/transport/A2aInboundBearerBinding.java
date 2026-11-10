package org.qubership.integration.platform.ai.a2a.transport;

import io.quarkus.security.UnauthorizedException;
import java.util.Map;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.transport.rest.context.RestContextKeys;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

/** Reads the inbound HTTP {@code Authorization} header and binds it to a turn's conversation id. */
public final class A2aInboundBearerBinding {

  private A2aInboundBearerBinding() {}

  public static String bearerFrom(RequestContext context) {
    return bearerFrom(context == null ? null : context.getCallContext());
  }

  public static String bearerFrom(ServerCallContext callContext) {
    if (callContext == null) {
      return null;
    }
    Object headersObj = callContext.getState().get(RestContextKeys.HEADERS_KEY);
    if (!(headersObj instanceof Map<?, ?> headers)) {
      return null;
    }
    for (Map.Entry<?, ?> entry : headers.entrySet()) {
      if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String value)) {
        continue;
      }
      if ("authorization".equalsIgnoreCase(name)) {
        return UserTokenStore.parseBearer(value);
      }
    }
    return null;
  }

  public static String requireBearer(RequestContext context) {
    return requireBearer(context == null ? null : context.getCallContext());
  }

  public static String requireBearer(ServerCallContext context) {
    String token = bearerFrom(context);
    if (token == null) {
      throw new UnauthorizedException("Bearer");
    }
    return token;
  }

  /** Stores the caller token for catalog calls keyed by {@code conversationId}. */
  public static void bind(String conversationId, String token) {
    UserTokenStore.put(conversationId, token);
    ToolSession.bind(conversationId);
  }

  public static void unbind(String conversationId, String token) {
    UserTokenStore.remove(conversationId, token);
    ToolSession.clear();
  }
}
