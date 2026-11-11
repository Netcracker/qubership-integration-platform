package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;

class ToolSessionTest {

  @AfterEach
  void clearSessionState() {
    ToolSession.clear();
    ProductCapabilityCaptureContext.unbind();
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void bindSetsResolveConversationIdWithoutChatExecutionService() {
    ToolSession.bind("conv-bind");
    assertEquals("conv-bind", ToolSession.resolveConversationId());
    ToolSession.clear();
    assertNull(ToolSession.resolveConversationId());
  }

  @Test
  void openHandleClearsOnClose() {
    try (ToolSession.Handle ignored = ToolSession.open("conv-handle")) {
      assertEquals("conv-handle", ToolSession.resolveConversationId());
    }
    assertNull(ToolSession.resolveConversationId());
  }

  @Test
  void propagateBindingInstallsConversationIdOnMultiSubscription() {
    AtomicReference<String> observed = new AtomicReference<>();
    ToolSession.bind("conv-propagate");
    Context toolContext = ToolSession.attachedContext();
    Multi<String> stream =
        ToolSession.propagateBinding(
            toolContext,
            Multi.createFrom().emitter(
                emitter -> {
                  observed.set(ToolSession.resolveConversationId());
                  emitter.complete();
                }));
    stream.collect().asList().await().indefinitely();
    assertEquals("conv-propagate", observed.get());
  }

  @Test
  void resolveConversationIdFallsBackToProductCapabilityCaptureContext() {
    ProductCapabilityCaptureContext.bindDiscovery("run-fallback", "conv-fallback", payload -> {});
    assertEquals("conv-fallback", ToolSession.resolveConversationId());
    ProductCapabilityCaptureContext.unbind();
    assertNull(ToolSession.resolveConversationId());
  }
}
