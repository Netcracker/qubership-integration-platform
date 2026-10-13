package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;

class E2eRecoveryFaultInjectorTest {

  @Test
  void corruptsOnlyTheScopedHttpUrlOnceAndPreservesOtherProperties() {
    var graph = new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph(
        "1.0", new org.qubership.integration.platform.ai.plan.model.ChainSection("UrlTest.1", "HTTP"),
        java.util.List.of(new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
            "http", "http-trigger", "HTTP", null, null, java.util.List.of(
                new org.qubership.integration.platform.ai.plan.model.PlanProperty("contextPath", "/approved"),
                new org.qubership.integration.platform.ai.plan.model.PlanProperty("httpMethodRestrict", "GET")))),
        java.util.List.of());
    var injector = new E2eRecoveryFaultInjector("UrlTest", "http-url-validation=CONTRACT_SHAPE:1");
    var corrupted = injector.corruptHttpUrl("run", graph);
    assertEquals("\"/approved\"", corrupted.nodes().getFirst().properties().getFirst().value());
    assertEquals(graph.nodes().getFirst().properties().get(1), corrupted.nodes().getFirst().properties().get(1));
    assertEquals(graph, injector.corruptHttpUrl("run", graph));
    assertEquals(graph, new E2eRecoveryFaultInjector("Other", "http-url-validation=CONTRACT_SHAPE:1")
        .corruptHttpUrl("run", graph));
    assertEquals(graph, new E2eRecoveryFaultInjector("", "").corruptHttpUrl("run", graph));
  }

  @Test
  void injectsEachConfiguredStageWithinItsOwnLimit() {
    E2eRecoveryFaultInjector injector =
        new E2eRecoveryFaultInjector(
            "RockyRecovery",
            "design-execution=CATALOG_RESOLUTION:1,materialization=CONTRACT_SHAPE:1");

    assertEquals(
        RecoveryCauseCode.CATALOG_RESOLUTION,
        injector.next("run-1", "RockyRecovery.1", "design-execution").orElseThrow());
    assertTrue(injector.next("run-1", "RockyRecovery.1", "design-execution").isEmpty());
    assertEquals(
        RecoveryCauseCode.CONTRACT_SHAPE,
        injector.next("run-1", "RockyRecovery.1", "materialization").orElseThrow());
    assertTrue(injector.next("run-1", "RockyRecovery.1", "materialization").isEmpty());
  }

  @Test
  void doesNotInjectForAnotherChainPrefix() {
    E2eRecoveryFaultInjector injector =
        new E2eRecoveryFaultInjector(
            "RockyRecovery", "design-execution=CATALOG_RESOLUTION:1");

    assertTrue(injector.next("run-1", "OtherChain", "design-execution").isEmpty());
  }

  @Test
  void rejectsMalformedPlans() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new E2eRecoveryFaultInjector(
                "RockyRecovery", "design-execution=CATALOG_RESOLUTION"));
  }
}
