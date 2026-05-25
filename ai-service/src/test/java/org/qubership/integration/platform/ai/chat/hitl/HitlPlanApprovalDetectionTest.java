package org.qubership.integration.platform.ai.chat.hitl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlPlanApprovalDetectionTest {

  @Test
  void phaseAOptionsWithAgreeOnlyDoesNotSuggestPlanApproval() {
    assertFalse(HitlTool.optionsSuggestPlanApproval(List.of("Use Pet service", "Cancel", "Agree")));
  }

  @Test
  void phaseCOptionsAgreeAndModifyPlanSuggestsPlanApproval() {
    assertTrue(HitlTool.optionsSuggestPlanApproval(List.of("Agree", "Modify plan")));
  }

  @Test
  void phaseCOptionsModifyPlanFirstStillMatches() {
    assertTrue(HitlTool.optionsSuggestPlanApproval(List.of("Modify plan", "Agree")));
  }
}
