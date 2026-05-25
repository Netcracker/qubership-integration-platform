package org.qubership.integration.platform.ai.chat.chainplan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImplementGateCoordinatorTest {

  @Test
  void optionsSuggestImplementGateMatchesGateOptions() {
    assertTrue(
        ImplementGateCoordinator.optionsSuggestImplementGate(
            ImplementGateCoordinator.GATE_OPTIONS));
  }

  @Test
  void isStartImplementationAnswerRecognizesOptionAndYes() {
    assertTrue(
        ImplementGateCoordinator.isStartImplementationAnswer(
            ImplementGateCoordinator.OPTION_START));
    assertTrue(ImplementGateCoordinator.isStartImplementationAnswer("Yes"));
  }

  @Test
  void isModifyPlanAnswerRecognizesModifyPlan() {
    assertTrue(ImplementGateCoordinator.isModifyPlanAnswer("Modify plan"));
    assertFalse(ImplementGateCoordinator.isStartImplementationAnswer("Modify plan"));
  }

  @Test
  void optionsSuggestImplementGateRejectsPlanApprovalOnly() {
    assertFalse(
        ImplementGateCoordinator.optionsSuggestImplementGate(List.of("Agree", "Modify plan")));
  }
}
