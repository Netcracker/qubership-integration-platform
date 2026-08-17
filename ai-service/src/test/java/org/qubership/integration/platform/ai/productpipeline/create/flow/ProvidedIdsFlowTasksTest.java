package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutor;

class ProvidedIdsFlowTasksTest {

  private static final String RUN_ID = "run-1";

  private ProductPipelineRunSupport runtime;
  private StageExecutor executor;
  private ProvidedIdsFlowTasks tasks;

  @BeforeEach
  void setUp() {
    runtime = mock(ProductPipelineRunSupport.class);
    executor = mock(StageExecutor.class);
    when(runtime.stageExecutor()).thenReturn(executor);
    when(runtime.applyStageLifecycle(eq(RUN_ID), any())).thenReturn(Multi.createFrom().empty());
    tasks = new ProvidedIdsFlowTasks(runtime);
  }

  @Test
  void requirementAnalysisApprovalMapsToRequirementWait() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("requirement-analysis");
    when(executor.execute(RUN_ID, "requirement-analysis"))
        .thenReturn(Uni.createFrom().item(approval("requirement-analysis")));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("WAIT_FOR_REQUIREMENT_APPROVAL", next.decision());
  }

  @Test
  void keepsRequirementBriefReviewInFrontOfTheApprovalWait() {
    CompilationArtifacts.Reference brief =
        new CompilationArtifacts.Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-1");
    PipelineSignal.Message narrative =
        new PipelineSignal.Message("The requirement brief has been captured.");
    PipelineSignal.Message review =
        new PipelineSignal.Message("**Goal:** Create a health check proxy chain\n\n**Facts:**\n");
    PipelineSignal.WaitingForApproval wait =
        new PipelineSignal.WaitingForApproval("requirement-analysis", brief, "approve");
    StageExecutionResult result =
        new StageExecutionResult(
            new StageDecision.WaitForApproval("requirement-analysis", brief, "approve"),
            List.of(narrative, wait));
    when(runtime.currentStageId(RUN_ID)).thenReturn("requirement-analysis");
    when(executor.execute(RUN_ID, "requirement-analysis"))
        .thenReturn(Uni.createFrom().item(result));
    when(runtime.applyStageLifecycle(eq(RUN_ID), any()))
        .thenReturn(Multi.createFrom().items(narrative, review, wait));

    tasks.executeCurrentStage(context());

    List<PipelineSignal> drained = tasks.drainSignals(RUN_ID);
    int reviewAt = drained.indexOf(review);
    int waitAt = drained.indexOf(wait);
    assertTrue(reviewAt >= 0, "expected compact brief review in drained signals");
    assertTrue(waitAt >= 0, "expected approval wait in drained signals");
    assertTrue(
        reviewAt < waitAt,
        "compact brief review must precede WaitingForApproval so chat does not replace the narrative");
  }

  @Test
  void designInputApprovalStillMapsToIdsWait() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("design-input");
    when(executor.execute(RUN_ID, "design-input"))
        .thenReturn(Uni.createFrom().item(approval("design-input")));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("WAIT_FOR_IDS_APPROVAL", next.decision());
  }

  @Test
  void designPlanningApprovalStillMapsToPlanWait() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("design-planning");
    when(executor.execute(RUN_ID, "design-planning"))
        .thenReturn(Uni.createFrom().item(approval("design-planning")));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("WAIT_FOR_PLAN_APPROVAL", next.decision());
  }

  @Test
  void retryDecisionPersistsDelayAndIncrementedRetryCount() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("work");
    when(executor.execute(RUN_ID, "work"))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new StageExecutionResult(
                        new StageDecision.Retry("work", Duration.ofMillis(50L)), List.of())));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("RETRY", next.decision());
    assertEquals("PT0.05S", next.retryDelay());
    assertEquals(1, next.technicalRetriesUsed());
    assertTrue(next.waitForRetry());
  }

  @Test
  void restoreAfterRetryKeepsRetryCountAndContinuesTheSameStage() {
    ProvidedIdsFlow.RunContext retrying =
        new ProvidedIdsFlow.RunContext(
            RUN_ID, "create-chain", "2", "manifest-sha", "RETRY", 1, "PT0.05S");

    ProvidedIdsFlow.RunContext next = tasks.restoreAfterRetry(retrying);

    assertEquals("CONTINUE", next.decision());
    assertEquals(1, next.technicalRetriesUsed());
    assertEquals("PT0.05S", next.retryDelay());
    assertFalse(next.waitForRetry());
  }

  @Test
  void restoreAfterRetrySeedsThePersistedRetryCountBeforeTheNextAttempt() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("work");
    when(executor.execute(RUN_ID, "work"))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new StageExecutionResult(
                        new StageDecision.Retry("work", Duration.ofMillis(50L)), List.of())));

    ProvidedIdsFlow.RunContext afterWait =
        tasks.restoreAfterRetry(
            new ProvidedIdsFlow.RunContext(
                RUN_ID, "create-chain", "2", "manifest-sha", "RETRY", 1, "PT0.05S"));
    tasks.executeCurrentStage(afterWait);

    org.mockito.Mockito.verify(runtime).restoreTechnicalRetryCount(RUN_ID, "work", 1);
  }

  @Test
  void reopenApprovalMapsToTheOwningRequirementApprovalWait() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("design-input");
    when(executor.execute(RUN_ID, "design-input"))
        .thenReturn(Uni.createFrom().item(reopen("design-input", "requirement-analysis")));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("WAIT_FOR_REQUIREMENT_APPROVAL", next.decision());
    assertTrue(next.waitForRequirementApproval());
    assertFalse(next.reenterStage());
  }

  @Test
  void reopenApprovalMapsToTheOwningIdsApprovalWait() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("design-planning");
    when(executor.execute(RUN_ID, "design-planning"))
        .thenReturn(Uni.createFrom().item(reopen("design-planning", "design-input")));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("WAIT_FOR_IDS_APPROVAL", next.decision());
    assertTrue(next.waitForIdsApproval());
    assertFalse(next.reenterStage());
  }

  @Test
  void reopenApprovalMapsToTheOwningPlanApprovalWait() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("design-execution");
    when(executor.execute(RUN_ID, "design-execution"))
        .thenReturn(Uni.createFrom().item(reopen("design-execution", "design-planning")));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("WAIT_FOR_PLAN_APPROVAL", next.decision());
    assertTrue(next.waitForPlanApproval());
    assertFalse(next.reenterStage());
  }

  private static ProvidedIdsFlow.RunContext context() {
    return new ProvidedIdsFlow.RunContext(RUN_ID, "create-chain", "2", "manifest-sha", null);
  }

  private static StageExecutionResult approval(String stageId) {
    return new StageExecutionResult(
        new StageDecision.WaitForApproval(
            stageId,
            new CompilationArtifacts.Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-1"),
            "approve"),
        List.of());
  }

  private static StageExecutionResult reopen(String failedStageId, String approvalStageId) {
    return new StageExecutionResult(
        new StageDecision.ReopenApproval(
            failedStageId, approvalStageId, "planning validation failed", List.of()),
        List.of());
  }
}
