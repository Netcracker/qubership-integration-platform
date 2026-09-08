package org.qubership.integration.platform.ai.integration.catalog.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;

class CatalogAuthorizationContextTest {

  private static final String TOKEN_ONE = "Bearer token-one";
  private static final String TOKEN_TWO = "Bearer token-two";

  @AfterEach
  void clearState() {
    CatalogAuthorizationContext.clear("conv-a");
    CatalogAuthorizationContext.clear("conv-b");
    ToolSession.clear();
  }

  @Test
  void bindClearAndResolveOnSameThread() {
    CatalogAuthorizationContext.bind("conv-a", TOKEN_ONE);
    assertEquals(TOKEN_ONE, CatalogAuthorizationContext.resolveAuthorization().orElseThrow());
    CatalogAuthorizationContext.clear("conv-a");
    assertTrue(CatalogAuthorizationContext.resolveAuthorization().isEmpty());
  }

  @Test
  void hotSwapUpdatesOutboundAuthorization() {
    CatalogAuthorizationContext.bind("conv-a", TOKEN_ONE);
    ToolSession.bind("conv-a");
    assertTrue(CatalogAuthorizationContext.update("conv-a", TOKEN_TWO));
    assertEquals(TOKEN_TWO, CatalogAuthorizationContext.resolveAuthorization().orElseThrow());
  }

  @Test
  void updateReturnsFalseForUnknownConversation() {
    assertFalse(CatalogAuthorizationContext.update("missing", TOKEN_ONE));
  }

  @Test
  void propagateBindingInstallsAuthorizationOnWorkerSubscription() {
    CatalogAuthorizationContext.bind("conv-a", TOKEN_ONE);
    ToolSession.bind("conv-a");
    Context toolContext = ToolSession.attachedContext();
    AtomicReference<String> observed = new AtomicReference<>();
    Multi<String> stream =
        CatalogAuthorizationContext.propagateWithToolSession(
            toolContext,
            Multi.createFrom().emitter(
                emitter -> {
                  observed.set(CatalogAuthorizationContext.resolveAuthorization().orElse(null));
                  emitter.complete();
                }));
    stream.collect().asList().await().indefinitely();
    assertEquals(TOKEN_ONE, observed.get());
  }

  @Test
  void propagateWithToolSessionKeepsConversationId() {
    CatalogAuthorizationContext.bind("conv-a", TOKEN_ONE);
    ToolSession.bind("conv-a");
    Context toolContext = ToolSession.attachedContext();
    AtomicReference<String> conversation = new AtomicReference<>();
    Multi<String> stream =
        CatalogAuthorizationContext.propagateWithToolSession(
            toolContext,
            Multi.createFrom().emitter(
                emitter -> {
                  conversation.set(ToolSession.resolveConversationId());
                  emitter.complete();
                }));
    stream.collect().asList().await().indefinitely();
    assertEquals("conv-a", conversation.get());
  }

  @Test
  void rejectNonBearerAuthorization() {
    assertThrows(IllegalArgumentException.class, () -> CatalogAuthorizationContext.bind("conv-a", "Basic abc"));
  }

  @Test
  void clearRemovesConversationAuthorization() {
    CatalogAuthorizationContext.bind("conv-a", TOKEN_ONE);
    ToolSession.bind("conv-a");
    CatalogAuthorizationContext.clear("conv-a");
    ToolSession.clear();
    assertTrue(CatalogAuthorizationContext.resolveAuthorization().isEmpty());
  }
}
