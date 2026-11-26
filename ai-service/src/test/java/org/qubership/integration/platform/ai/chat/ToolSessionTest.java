package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  void openHandleRestoresPreviousBindingOnClose() {
    ToolSession.bind("conv-turn");
    try (ToolSession.Handle ignored = ToolSession.open("conv-skill")) {
      assertEquals("conv-skill", ToolSession.resolveConversationId());
    }
    assertEquals("conv-turn", ToolSession.resolveConversationId());
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
  void propagatedBindingLeavesNoStateOnSubscribingOrDeliveringThreads() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // The jboss MDC is inheritable, so a worker created while bound would copy the binding.
      pool.submit(() -> null).get();
      ToolSession.bind("conv-cross-thread");
      Context context = ToolSession.attachedContext();
      ToolSession.clear();
      List<String> seen =
          ToolSession.propagateBinding(
                  context, Multi.createFrom().items("a", "b").runSubscriptionOn(pool))
              .onItem()
              .transform(item -> ToolSession.resolveConversationId())
              .collect()
              .asList()
              .await()
              .indefinitely();

      assertEquals(List.of("conv-cross-thread", "conv-cross-thread"), seen);
      assertNull(ToolSession.resolveConversationId());
      assertNull(MDC.get(ChatMdc.CONVERSATION_ID));
      assertNull(pool.submit(ToolSession::resolveConversationId).get());
      assertNull(pool.submit(() -> MDC.get(ChatMdc.CONVERSATION_ID)).get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void resolveConversationIdFallsBackToProductCapabilityCaptureContext() {
    ProductCapabilityCaptureContext.bindDiscovery("run-fallback", "conv-fallback", payload -> {});
    assertEquals("conv-fallback", ToolSession.resolveConversationId());
    ProductCapabilityCaptureContext.unbind();
    assertNull(ToolSession.resolveConversationId());
  }
}
