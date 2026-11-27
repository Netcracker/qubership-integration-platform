package org.qubership.integration.platform.ai.a2a.transport;

import java.util.Map;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.transport.rest.context.RestContextKeys;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

/** Reads the bearer token from the inbound HTTP {@code Authorization} header. */
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
}
