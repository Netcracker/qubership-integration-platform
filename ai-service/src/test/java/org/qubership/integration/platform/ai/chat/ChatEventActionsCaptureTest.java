package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.runtime.HaltRecoveryGuard;
import org.qubership.integration.platform.ai.productpipeline.runtime.SemanticRecoveryState;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ChatEventActionsCaptureTest {

  @Test
  void actionsForClarifyFillsTheFacadeSeamOfSemanticRecoveryState() {
    SemanticRecoveryState runtime =
        SemanticRecoveryState.captureRuntime(
            RunStatus.WAITING_FOR_INPUT,
            "planning",
            StageStatus.WAITING_FOR_INPUT,
            PipelineGates.STAGE_RETRY,
            "The plan failed validation.",
            new SemanticRecoveryState.RemainingAttempts(1, 2));
    List<String> actions =
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                "The plan failed validation.", List.of(), PipelineGates.STAGE_RETRY));
    SemanticRecoveryState facade = runtime.withCardActions(actions);
    assertEquals(List.of(PipelineGates.RETRY_ACTION), facade.cardActions());
    assertEquals(
        SemanticRecoveryState.Component.CARD_ACTIONS,
        ((SemanticRecoveryState.CompareResult.Advanced) runtime.compareTo(facade)).component());
  }

  @Test
  void anEscalatedClarifyOffersOnlyActionsThatStillWork() {
    List<String> actions =
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                HaltRecoveryGuard.OWNER_ALREADY_REOPENED.cardSentence(),
                List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
                PipelineGates.STAGE_ESCALATED));
    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), actions);
  }
}
