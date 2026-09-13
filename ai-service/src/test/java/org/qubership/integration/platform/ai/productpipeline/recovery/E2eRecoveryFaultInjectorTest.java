package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;

class E2eRecoveryFaultInjectorTest {

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
