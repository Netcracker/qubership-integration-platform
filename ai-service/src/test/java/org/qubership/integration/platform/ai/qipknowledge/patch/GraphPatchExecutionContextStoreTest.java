package org.qubership.integration.platform.ai.qipknowledge.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class GraphPatchExecutionContextStoreTest {

  private final GraphPatchExecutionContextStore store = new GraphPatchExecutionContextStore();

  @AfterEach
  void tearDown() {
    store.clear("conv-1", "cip-routing-generator");
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
  }

  @Test
  void getSeesContextBoundOnAnotherThread() throws Exception {
    GraphPatchExecutionContext context = sampleContext("cip-routing-generator");
    store.set("conv-1", "cip-routing-generator", context);

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Optional<GraphPatchExecutionContext>> seen = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                seen.set(store.get("conv-1", "cip-routing-generator"));
              } finally {
                done.countDown();
              }
            });
    worker.start();
    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertTrue(seen.get().isPresent());
    assertEquals(context, seen.get().orElseThrow());
  }

  @Test
  void currentUsesMdcKeys() {
    GraphPatchExecutionContext context = sampleContext("cip-timeout-generator");
    store.set("conv-1", "cip-timeout-generator", context);
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-1");
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, "cip-timeout-generator");

    assertTrue(store.current().isPresent());
    assertEquals(context, store.current().orElseThrow());
  }

  private static GraphPatchExecutionContext sampleContext(String skillId) {
    ChainPlanGraph graph =
        new ChainPlanGraph("1.0", new ChainSection("id", "name"), List.of(), List.of());
    return new GraphPatchExecutionContext(
        "run-1",
        skillId,
        "req-1",
        "graph-1",
        "compiler-1",
        "24.4",
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary"),
        List.of(),
        graph,
        GraphPatchOwnershipPolicy.denyAll(),
        "");
  }
}
