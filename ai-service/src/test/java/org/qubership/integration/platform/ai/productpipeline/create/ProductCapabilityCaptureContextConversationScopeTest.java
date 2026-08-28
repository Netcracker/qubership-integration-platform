package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;

/**
 * A stage binds on one thread and releases on another, so a pooled worker keeps its binding. Every
 * lookup must therefore be scoped to a conversation, not to the thread that happens to run it.
 */
class ProductCapabilityCaptureContextConversationScopeTest {

  @AfterEach
  void unbind() {
    ProductCapabilityCaptureContext.unbind("conv-a");
    ProductCapabilityCaptureContext.unbind("conv-b");
  }

  @Test
  void doesNotHandAnotherConversationsApprovedDraftToAToolOnTheSameThread() throws Exception {
    ProductCapabilityCaptureContext.bindAnalysis(
        "run-b", "conv-b", draft("conv-b draft"), payload -> {});
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      // The worker keeps conv-a's binding: its stage released on a different thread.
      worker
          .submit(
              () ->
                  ProductCapabilityCaptureContext.bindAnalysis(
                      "run-a", "conv-a", draft("conv-a draft"), payload -> {}))
          .get();

      // Proves the worker really is carrying the foreign binding this lookup has to reject.
      assertEquals(
          "conv-a",
          worker
              .submit(() -> ProductCapabilityCaptureContext.current().orElseThrow().conversationId())
              .get());

      RequirementDraft seen =
          worker
              .submit(
                  () ->
                      ProductCapabilityCaptureContext.approvedDraft("conv-b").orElseThrow())
              .get();
      assertEquals("conv-b draft", seen.assembledText());

      assertTrue(worker.submit(() -> ProductCapabilityCaptureContext.isBound("conv-b")).get());
      assertFalse(worker.submit(() -> ProductCapabilityCaptureContext.isBound("conv-c")).get());
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void releasingByConversationIdClearsABindingTakenOnAnotherThread() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      worker
          .submit(
              () ->
                  ProductCapabilityCaptureContext.bindDiscovery("run-a", "conv-a", payload -> {}))
          .get();
      assertTrue(ProductCapabilityCaptureContext.isBound("conv-a"));

      ProductCapabilityCaptureContext.unbind("conv-a");

      assertFalse(ProductCapabilityCaptureContext.isBound("conv-a"));
    } finally {
      worker.shutdownNow();
    }
  }

  private static RequirementDraft draft(String text) {
    return new RequirementDraft(
        true, text, DraftDecision.READY_FOR_PLAN, List.<String>of(), null, null);
  }
}
