package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

/**
 * Blocks for a renewed token before the HTTP request is sent, so the client's read timeout does not
 * cut the wait short. The priority sits just before Fault Tolerance, which runs at {@code
 * PLATFORM_AFTER + 10}.
 */
@AwaitUsableTurnBearer
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER - 100)
public class AwaitUsableTurnBearerInterceptor {

  @AroundInvoke
  Object awaitUsableToken(InvocationContext context) throws Exception {
    UserTokenStore.currentUsable();
    return context.proceed();
  }
}
