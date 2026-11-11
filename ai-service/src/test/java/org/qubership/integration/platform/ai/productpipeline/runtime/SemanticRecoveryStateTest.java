package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class SemanticRecoveryStateTest {

  @Test
  void identicalCapturesCompareAsUnchanged() {
    SemanticRecoveryState state = sample(PipelineGates.STAGE_RETRY, List.of(), 1, 2);
    assertInstanceOf(SemanticRecoveryState.CompareResult.Unchanged.class, state.compareTo(state));
  }

  @Test
  void aDifferentGateComparesAsAdvanced() {
    SemanticRecoveryState before = sample(PipelineGates.STAGE_RETRY, List.of(), 1, 2);
    SemanticRecoveryState after = sample(PipelineGates.STAGE_REVISE, List.of(), 1, 2);
    SemanticRecoveryState.CompareResult result = before.compareTo(after);
    assertInstanceOf(SemanticRecoveryState.CompareResult.Advanced.class, result);
    assertEquals(
        SemanticRecoveryState.Component.GATE,
        ((SemanticRecoveryState.CompareResult.Advanced) result).component());
  }

  @Test
  void cardActionsFromTheFacadeSeamAreTheDifferingComponent() {
    SemanticRecoveryState runtime = sample(PipelineGates.STAGE_RETRY, List.of(), 1, 2);
    SemanticRecoveryState facade = runtime.withCardActions(List.of(PipelineGates.RETRY_ACTION));
    SemanticRecoveryState.CompareResult result = runtime.compareTo(facade);
    assertEquals(
        SemanticRecoveryState.Component.CARD_ACTIONS,
        ((SemanticRecoveryState.CompareResult.Advanced) result).component());
  }

  @Test
  void remainingAttemptsAreATupleNotASingleMinimum() {
    SemanticRecoveryState.RemainingAttempts remaining =
        new SemanticRecoveryState.RemainingAttempts(1, 2);
    assertEquals(1, remaining.semanticRepairsRemaining());
    assertEquals(2, remaining.causalReopensRemaining());
  }

  private static SemanticRecoveryState sample(
      String gateId, List<String> actions, int repairs, int reopens) {
    return new SemanticRecoveryState(
        RunStatus.WAITING_FOR_INPUT,
        "planning",
        StageStatus.WAITING_FOR_INPUT,
        gateId,
        actions,
        "The plan failed validation.",
        new SemanticRecoveryState.RemainingAttempts(repairs, reopens));
  }
}
