package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;

class RecoveryExecutorTest {

  private static final Reference BRIEF_REF =
      new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "brief-hash");
  private static final Reference GRAPH_REF =
      new Reference(Kind.CHAIN_PLAN_GRAPH, "graph-1", "graph-hash");
  private static final Reference PLAN_REF =
      new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "plan-hash");
  private static final ProfileStage FAILED_STAGE =
      new ProfileStage(
          "design-execution",
          "design-execution-capability",
          List.of(),
          List.of(),
          null,
          null,
          new RetryPolicy(0, 0));

  @Test
  void reviseBriefReopensRequirementAnalysis() {
    StageDecision decision =
        RecoveryExecutor.execute(
            decision(RecoveryCauseClass.BRIEF_DEFECT, RecoveryAction.REVISE_BRIEF, "", "Fix it."),
            null,
            FAILED_STAGE,
            false);

    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, decision);
    assertEquals("design-execution", reopen.stageId());
    assertEquals("requirement-analysis", reopen.producerStageId());
  }

  @Test
  void catalogWriteParksBriefRevision() {
    StageDecision decision =
        RecoveryExecutor.execute(
            decision(RecoveryCauseClass.BRIEF_DEFECT, RecoveryAction.REVISE_BRIEF, "", "Fix it."),
            null,
            FAILED_STAGE,
            true);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, decision);
    assertEquals(PipelineGates.STAGE_RETRY, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertFalse(wait.prompt().contains("__OWNER_CANDIDATES__"));
  }

  @Test
  void parkWaitDoesNotOfferOwnerChoice() {
    StageDecision decision =
        RecoveryExecutor.execute(
            decision(
                RecoveryCauseClass.UNCLASSIFIED,
                RecoveryAction.PARK,
                "",
                "The failure needs review."),
            null,
            FAILED_STAGE,
            false);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, decision);
    assertEquals(PipelineGates.STAGE_RETRY, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals("The failure needs review.", PipelineGates.strip(wait.prompt()));
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
  }

  @Test
  void derivationDefectRegenerateRetriesCurrentStageWithoutReopeningBrief() {
    RecoveryEvidence evidence = derivationEvidence(GRAPH_REF);
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            GRAPH_REF,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Regenerate the graph without changing the brief.");

    StageDecision.Retry retry =
        assertInstanceOf(
            StageDecision.Retry.class,
            RecoveryExecutor.execute(decision, evidence, null, FAILED_STAGE, false, false));

    assertEquals("design-execution", retry.stageId());
    assertEquals(BRIEF_REF, evidence.approvedBriefRef());
  }

  @Test
  void derivationDefectRegenerateReopensUpstreamPlanningProducer() {
    RecoveryEvidence evidence = derivationEvidence(PLAN_REF);
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            PLAN_REF,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Regenerate the plan.");

    StageDecision.ReopenProducer reopen =
        assertInstanceOf(
            StageDecision.ReopenProducer.class,
            RecoveryExecutor.execute(decision, evidence, null, FAILED_STAGE, false, false));

    assertEquals("design-execution", reopen.stageId());
    assertEquals("design-planning", reopen.producerStageId());
    assertNotEquals("requirement-analysis", reopen.producerStageId());
  }

  @Test
  void identicalRegenerateAsksTheAuthorWithoutReopeningBrief() {
    RecoveryEvidence evidence = derivationEvidence(GRAPH_REF);
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            GRAPH_REF,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "The graph is still invalid.");

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class,
            RecoveryExecutor.execute(decision, evidence, null, FAILED_STAGE, false, true));

    assertEquals(
        PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals("The graph is still invalid.", PipelineGates.strip(wait.prompt()));
    assertTrue(PipelineGates.ownerCandidatesOf(wait.prompt()).isEmpty());
  }

  @Test
  void technicalFailureRetriesSameStageWithoutReopeningAnalysis() {
    RecoveryEvidence evidence = technicalEvidence();
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.TECHNICAL_FAILURE,
            GRAPH_REF,
            List.of(evidence.failureId()),
            RecoveryAction.RETRY_OPERATION,
            List.of(),
            "",
            "Retry the catalog lookup.");

    ProfileStage stageWithDelay =
        new ProfileStage(
            "design-execution",
            "design-execution-capability",
            List.of(),
            List.of(),
            null,
            null,
            new RetryPolicy(1, 250L));
    StageDecision.Retry retry =
        assertInstanceOf(
            StageDecision.Retry.class,
            RecoveryExecutor.execute(decision, evidence, null, stageWithDelay, false, false));

    assertEquals("design-execution", retry.stageId());
    assertEquals(Duration.ofMillis(250), retry.delay());
  }

  @Test
  void catalogWriteParksRegenerateArtifact() {
    RecoveryEvidence evidence = derivationEvidence(GRAPH_REF);
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            GRAPH_REF,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Regenerate the graph.");

    StageDecision.WaitForInput wait =
        assertInstanceOf(
            StageDecision.WaitForInput.class,
            RecoveryExecutor.execute(decision, evidence, null, FAILED_STAGE, true, false));

    assertEquals(PipelineGates.STAGE_RETRY, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertFalse(wait.prompt().contains("__OWNER_CANDIDATES__"));
  }

  @Test
  void askUserDoesNotRepeatAnIdenticalSummaryAndQuestion() {
    String captureError =
        "CONTRACT_SHAPE: Trigger node 'trigger-onTaskResult' must have exactly one outgoing edge.";
    StageDecision decision =
        RecoveryExecutor.execute(
            decision(
                RecoveryCauseClass.DERIVATION_DEFECT,
                RecoveryAction.ASK_USER,
                captureError,
                captureError),
            null,
            FAILED_STAGE,
            false);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, decision);
    String body = PipelineGates.strip(wait.prompt());
    assertEquals(captureError, body);
    assertEquals(1, body.split(java.util.regex.Pattern.quote(captureError), -1).length - 1);
  }

  @Test
  void askUserWaitUsesClarificationWithoutLeakedStageIds() {
    StageDecision decision =
        RecoveryExecutor.execute(
            decision(
                RecoveryCauseClass.BRIEF_DEFECT,
                RecoveryAction.ASK_USER,
                "Should we go back to requirement-analysis?",
                "design-execution needs a product decision."),
            null,
            FAILED_STAGE,
            false);

    StageDecision.WaitForInput wait =
        assertInstanceOf(StageDecision.WaitForInput.class, decision);
    assertEquals(
        PipelineGates.STAGE_CLARIFICATION,
        PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertFalse(wait.prompt().contains("__OWNER_CANDIDATES__"));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("requirement-analysis"));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("design-execution"));
    assertFalse(PipelineGates.strip(wait.prompt()).contains("go back to"));
  }

  private static RecoveryEvidence derivationEvidence(Reference faultRef) {
    return new RecoveryEvidence(
        1,
        "failure-1",
        "MISSING_REQUIRED_PROPERTY",
        "design-execution",
        BRIEF_REF,
        GRAPH_REF,
        List.of(faultRef),
        List.of(),
        null,
        List.of());
  }

  private static RecoveryEvidence technicalEvidence() {
    return new RecoveryEvidence(
        1,
        "failure-1",
        "TIMEOUT",
        "design-execution",
        BRIEF_REF,
        GRAPH_REF,
        List.of(GRAPH_REF),
        List.of(),
        new TechnicalFailureRecord(
            true, 1, "catalog", "lookup", "30s", "corr-1", "TimeoutException", "timed out", "", ""),
        List.of());
  }

  private static RecoveryDecision decision(
      RecoveryCauseClass causeClass,
      RecoveryAction action,
      String question,
      String userSummary) {
    return new RecoveryDecision(
        causeClass,
        BRIEF_REF,
        List.of("failure-1"),
        action,
        List.of(),
        question,
        userSummary);
  }
}
