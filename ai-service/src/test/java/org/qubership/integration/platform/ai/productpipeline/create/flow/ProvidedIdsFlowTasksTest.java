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
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
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
    when(runtime.retryPolicy(RUN_ID, "work")).thenReturn(new RetryPolicy(3, 100L, 2.0, 250L));
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
  void retryDecisionsGrowWithinJitterBoundsAndClampAtTheMaximum() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("work");
    when(executor.execute(RUN_ID, "work"))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new StageExecutionResult(
                        new StageDecision.Retry("work", Duration.ofMillis(100L)), List.of())),
            Uni.createFrom()
                .item(
                    new StageExecutionResult(
                        new StageDecision.Retry("work", Duration.ofMillis(100L)), List.of())),
            Uni.createFrom()
                .item(
                    new StageExecutionResult(
                        new StageDecision.Retry("work", Duration.ofMillis(100L)), List.of())));

    ProvidedIdsFlow.RunContext first = tasks.executeCurrentStage(context());
    ProvidedIdsFlow.RunContext second = tasks.executeCurrentStage(first);
    ProvidedIdsFlow.RunContext third = tasks.executeCurrentStage(second);

    assertEquals("RETRY", first.decision());
    assertEquals(1, first.technicalRetriesUsed());
    assertTrue(delayMs(first) >= 80 && delayMs(first) <= 120);
    assertTrue(delayMs(second) >= 160 && delayMs(second) <= 240);
    assertEquals(250L, delayMs(third));
  }

  @Test
  void nonRetryDecisionResetsTheUsedAttemptCount() {
    when(runtime.currentStageId(RUN_ID)).thenReturn("work");
    when(executor.execute(RUN_ID, "work"))
        .thenReturn(
            Uni.createFrom().item(new StageExecutionResult(new StageDecision.Continue("work"), List.of())));

    ProvidedIdsFlow.RunContext next =
        tasks.executeCurrentStage(
            new ProvidedIdsFlow.RunContext(
                RUN_ID, "create-chain", "2", "manifest-sha", "RETRY", 2, "PT0.2S"));

    assertEquals("CONTINUE", next.decision());
    assertEquals(null, next.technicalRetriesUsed());
  }

  @Test
  void aStageExecutionThatFailsSettlesTheContextInsteadOfPropagating() {
    String prompt = PipelineGates.tag(PipelineGates.STAGE_RETRY, "catalog lookup broke");
    when(runtime.currentStageId(RUN_ID)).thenReturn("work");
    when(executor.execute(RUN_ID, "work"))
        .thenReturn(Uni.createFrom().failure(new IllegalStateException("catalog lookup broke")));
    when(executor.haltOnEscapedFailure(eq(RUN_ID), any()))
        .thenReturn(
            new StageExecutionResult(
                new StageDecision.WaitForInput("work", prompt),
                List.of(new PipelineSignal.WaitingForInput("work", prompt))));

    ProvidedIdsFlow.RunContext next = tasks.executeCurrentStage(context());

    assertEquals("WAIT_FOR_INPUT", next.decision());
    assertTrue(tasks.settled(RUN_ID));
    assertEquals(
        List.of(new PipelineSignal.WaitingForInput("work", prompt)), tasks.drainSignals(RUN_ID));
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

  private static ProvidedIdsFlow.RunContext context() {
    return new ProvidedIdsFlow.RunContext(RUN_ID, "create-chain", "2", "manifest-sha", null);
  }

  private static long delayMs(ProvidedIdsFlow.RunContext context) {
    return Duration.parse(context.retryDelay()).toMillis();
  }

  private static StageExecutionResult approval(String stageId) {
    return new StageExecutionResult(
        new StageDecision.WaitForApproval(
            stageId,
            new CompilationArtifacts.Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-1"),
            "approve"),
        List.of());
  }
}
