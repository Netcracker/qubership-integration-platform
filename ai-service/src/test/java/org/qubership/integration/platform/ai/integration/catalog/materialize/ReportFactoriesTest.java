package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport.StageFailure;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport.StageOutcome;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportFactoriesTest {

  @Test
  void skeletonSetsFields() {
    StageFailure sf = ReportFactories.skeleton("c1", "http-trigger", "p1", "bad placement");
    assertEquals("c1", sf.clientId);
    assertEquals("http-trigger", sf.type);
    assertEquals("p1", sf.parentElementId);
    assertEquals("bad placement", sf.reason);
  }

  @Test
  void connectionSetsFields() {
    StageFailure sf = ReportFactories.connection("a", "b", "boom");
    assertEquals("a", sf.fromClientId);
    assertEquals("b", sf.toClientId);
    assertEquals("boom", sf.reason);
  }

  @Test
  void propertySetsFields() {
    StageFailure sf = ReportFactories.property("c1", "elem-1", "patch failed");
    assertEquals("c1", sf.clientId);
    assertEquals("elem-1", sf.elementId);
    assertEquals("patch failed", sf.reason);
  }

  @Test
  void finalizeStageNullFailuresOkTrueEmptyList() {
    StageOutcome o = ReportFactories.finalizeStage(null);
    assertTrue(o.ok);
    assertNotNull(o.failures);
    assertTrue(o.failures.isEmpty());
  }

  @Test
  void finalizeStageNonEmptyFailuresOkFalse() {
    StageFailure one = ReportFactories.skeleton(null, null, null, "x");
    StageOutcome o = ReportFactories.finalizeStage(List.of(one));
    assertFalse(o.ok);
    assertEquals(1, o.failures.size());
    assertEquals("x", o.failures.get(0).reason);
  }
}
