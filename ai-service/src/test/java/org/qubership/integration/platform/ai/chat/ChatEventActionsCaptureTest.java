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
  void contextualRetryOffersSemanticCreationActions() {
    List<String> actions =
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                "The provider temporarily limited requests.",
                List.of(),
                PipelineGates.RECOVERY_RETRY_TECHNICAL));

    assertEquals(
        List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION), actions);
  }

  @Test
  void mappingGapWithoutASourceHidesPassThroughAndDescribeActions() {
    assertEquals(
        List.of(),
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                "Some data mappings are still missing before design can continue.",
                List.of("INITIALIZATION: trigger → first outbound call (no ENDPOINT fact)"),
                PipelineGates.MAPPING_GAP)));
  }

  @Test
  void mappingGapWithASourceStillOffersPassThroughAndDescribe() {
    assertEquals(
        ChatEvent.MAPPING_GAP_ACTIONS,
        ChatEvent.actionsForClarify(
            new CreateChainPendingAction.Clarify(
                "Some data mappings are still missing before design can continue.",
                List.of("INITIALIZATION: ENDPOINT \"GET /orders\" → SERVICE_CALL \"Create order\""),
                PipelineGates.MAPPING_GAP)));
  }
}
