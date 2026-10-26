package org.qubership.integration.platform.ai.productpipeline.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;

class StageRepairEvidenceTest {

  @Test
  void typedOriginAndFieldReachTheCorrectingProducer() {
    String cause = "{\"causeCode\":\"SECURITY_POLICY\",\"findings\":[{"
        + "\"code\":\"security-1\",\"message\":\"Use the confirmed access policy.\","
        + "\"blocker\":true,\"originRecordId\":\"http-trigger\","
        + "\"fieldPointer\":\"/properties/accessControlType\"}],\"requestedFact\":\"\"}";
    StageRepairEvidence evidence = StageRepairEvidence.from(contextWithAttributes(Map.of(
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "validation failed",
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "design-execution",
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_SKILL_ATTR, "cip-security-generator",
        ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, "security-1: Use the confirmed access policy.",
        ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR, cause)));

    String producerRequest = evidence.repairTextForSkill("cip-security-generator");

    assertTrue(producerRequest.contains("\"originRecordId\":\"http-trigger\""));
    assertTrue(producerRequest.contains("\"fieldPointer\":\"/properties/accessControlType\""));
    assertEquals("", evidence.repairTextForSkill("cip-transformation-generator"));
  }

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
  void repairTurnCarriesRecoveryEvidenceRef() {
    Map<String, Object> attributes =
        Map.of(
            ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
            "Phase 5 plan validation failed",
            ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR,
            "recovery-evidence-hash");
    StageExecutionContext context = contextWithAttributes(attributes);

    StageRepairEvidence evidence = StageRepairEvidence.from(context);
    assertEquals("recovery-evidence-hash", evidence.recoveryEvidenceRef());
  }

  @Test
  void cleanRebuildAppliesOnlyToTheSelectedRecoveryEvidence() {
    StageExecutionContext selected =
        contextWithAttributes(
            Map.of(
                ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
                "planner rejected",
                ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR,
                "evidence-a",
                ProductPipelineRunSupport.DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR,
                "evidence-a"));
    StageExecutionContext laterFailure =
        contextWithAttributes(
            Map.of(
                ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
                "planner rejected again",
                ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR,
                "evidence-b",
                ProductPipelineRunSupport.DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR,
                "evidence-a"));

    assertTrue(
        StageRepairEvidence.isCleanDesignPlanRebuild(
            selected, StageRepairEvidence.from(selected)));
    assertFalse(
        StageRepairEvidence.isCleanDesignPlanRebuild(
            laterFailure, StageRepairEvidence.from(laterFailure)));
  }

  @Test
  void cleanDesignInputRebuildUsesItsOwnSelectedRecoveryEvidence() {
    StageExecutionContext selected =
        contextWithAttributes(
            Map.of(
                ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
                "semantic design rejected",
                ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR,
                "evidence-design",
                ProductPipelineRunSupport.DESIGN_INPUT_CLEAN_REBUILD_EVIDENCE_REF_ATTR,
                "evidence-design"));

    assertTrue(
        StageRepairEvidence.isCleanDesignInputRebuild(
            selected, StageRepairEvidence.from(selected)));
    assertFalse(
        StageRepairEvidence.isCleanDesignPlanRebuild(
            selected, StageRepairEvidence.from(selected)));
  }

  @Test
  void executionRepairReachesOnlyTheFailedSkillWithoutRawArguments() {
    StageExecutionContext context = contextWithAttributes(Map.of(
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "MALFORMED_ARGUMENT " + "CIP:GEN-000005 ".repeat(4000),
        ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, "CONTRACT_FAILURE",
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "design-execution",
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_SKILL_ATTR, "cip-error-handling-generator",
        ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, "INVALID_JSON at /patch",
        ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, "CONTRACT_SHAPE",
        ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR, "evidence-1"));
    StageRepairEvidence evidence = StageRepairEvidence.from(context);

    String repair = evidence.repairTextForSkill("cip-error-handling-generator");

    assertTrue(repair.contains("INVALID_JSON at /patch"));
    assertTrue(repair.contains("CONTRACT_SHAPE"));
    assertTrue(repair.contains("evidence-1"));
    assertFalse(repair.contains("MALFORMED_ARGUMENT"));
    assertFalse(repair.contains("CIP:GEN-000005"));
    assertEquals("", evidence.repairTextForSkill("cip-naming-generator"));
    assertEquals("", evidence.repairTextForSkill("cip-transformation-generator"));
    assertTrue(evidence.errorEvidence().contains("MALFORMED_ARGUMENT"));
  }

  @Test
  void aLegacyShapeFailureGetsCorrectiveGuidanceWithoutItsRejectedArguments() {
    StageRepairEvidence evidence = new StageRepairEvidence(
        "CONTRACT_FAILURE", "design-execution", "", "params 'broken JSON'", "",
        "evidence-legacy", List.of(), "cip-naming-generator", "CONTRACT_SHAPE");

    String repair = evidence.repairTextForSkill("cip-naming-generator");

    assertTrue(repair.contains("Tool arguments could not be decoded"));
    assertTrue(repair.contains("active capture tool schema"));
    assertFalse(repair.contains("broken JSON"));
  }

  @Test
  void aRejectedGraphRepairCallsOnlyTheOwningGenerator() throws Exception {
    String graph = new ObjectMapper().writeValueAsString(new ChainPlanGraph(
        "1.0", new ChainSection("parcel", "Parcel"), List.of(), List.of()));
    StageRepairEvidence evidence = new StageRepairEvidence(
        "VALIDATION_FAILURE", "design-execution",
        "MISSING_REQUIRED_SCRIPT_LITERAL: restore the author literal",
        "quality validation failed", "", "evidence-script", List.of(),
        "cip-script-generator", "MISSING_REQUIRED_PROPERTY", "", graph);

    assertTrue(evidence.keepsRejectedGraph());
    assertFalse(evidence.defersGenerator("cip-script-generator"));
    assertTrue(evidence.defersGenerator("cip-http-trigger-endpoint-generator"));
    assertTrue(evidence.repairTextForSkill("cip-script-generator").contains("author literal"));
    assertEquals("", evidence.repairTextForSkill("cip-naming-generator"));
  }

  @Test
  void anUnscopedRepairStillCallsEveryGenerator() {
    StageRepairEvidence evidence = new StageRepairEvidence(
        "VALIDATION_FAILURE", "design-execution", "quality validation failed",
        "quality validation failed", "", "evidence-open", List.of(),
        "", "MISSING_REQUIRED_PROPERTY");

    assertFalse(evidence.keepsRejectedGraph());
    assertFalse(evidence.defersGenerator("cip-naming-generator"));
  }

  @Test
  void staleSemanticFailureCannotBecomeCompilerRepairEvidence() {
    StageRepairEvidence evidence = StageRepairEvidence.from(contextWithAttributes(Map.of(
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "semantic capture rejected",
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "design-input",
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_SKILL_ATTR, "cip-naming-generator")));

    assertEquals("", evidence.repairTextForSkill("cip-naming-generator"));
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
