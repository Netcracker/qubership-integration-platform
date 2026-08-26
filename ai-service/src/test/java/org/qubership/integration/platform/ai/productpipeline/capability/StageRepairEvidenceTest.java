package org.qubership.integration.platform.ai.productpipeline.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;

class StageRepairEvidenceTest {

  @Test
  void firstTurnIsNotARepairAndCarriesNoEvidence() {
    StageExecutionContext context = contextWithAttributes(Map.of());

    assertFalse(StageRepairEvidence.isRepairTurn(context));
    assertNull(StageRepairEvidence.from(context));
  }

  @Test
  void repairTurnIsRecognizedAndCarriesTheHaltAttributesVerbatim() {
    Map<String, Object> attributes =
        Map.of(
            ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
            "Phase 5 plan validation failed",
            ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR,
            "VALIDATION_FAILURE",
            ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR,
            "design-execution",
            ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR,
            "security-1: External route requires accessControlType=RBAC (blocker)",
            ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR,
            "add rbac");
    StageExecutionContext context = contextWithAttributes(attributes);

    assertTrue(StageRepairEvidence.isRepairTurn(context));
    StageRepairEvidence evidence = StageRepairEvidence.from(context);
    assertEquals("VALIDATION_FAILURE", evidence.outcomeClass());
    assertEquals("design-execution", evidence.failedStageId());
    assertEquals(
        "security-1: External route requires accessControlType=RBAC (blocker)",
        evidence.findings());
    assertEquals("Phase 5 plan validation failed", evidence.errorEvidence());
    assertEquals("add rbac", evidence.haltFollowUpText());
    assertTrue(evidence.hasEvidence());
  }

  @Test
  void nullContextIsTreatedAsAFirstTurn() {
    assertFalse(StageRepairEvidence.isRepairTurn(null));
  }

  private static StageExecutionContext contextWithAttributes(Map<String, Object> attributes) {
    return new StageExecutionContext(
        "run-1",
        "conv-1",
        "some-stage",
        "exec-1",
        "attempt-1",
        null,
        null,
        java.util.List.of(),
        attributes);
  }
}
