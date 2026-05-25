package org.qubership.integration.platform.ai.chat.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HitlPlanningPhaseGuardTest {

  @Test
  void noPlanBlocksProceedWithCreatingChainQuestion() {
    String q =
        "Proceed with creating the chain and implementing the plan for the Petstore Order Gateway?";
    assertNotNull(HitlPlanningPhaseGuard.rejectOrNull(q, false, false));
  }

  @Test
  void noPlanAllowsServicePickQuestion() {
    String q =
        "Which catalog system should bind getPetById: Pet service (INTERNAL) or Pet store"
            + " (IMPLEMENTED)?";
    assertNull(HitlPlanningPhaseGuard.rejectOrNull(q, false, false));
  }

  @Test
  void planApprovedAllowsExecutionWording() {
    String q = "Proceed with creating the chain now";
    assertNull(HitlPlanningPhaseGuard.rejectOrNull(q, true, true));
  }

  @Test
  void planCapturedNotApprovedBlocksExecutePlan() {
    assertNotNull(HitlPlanningPhaseGuard.rejectOrNull("Execute the plan", true, false));
  }
}
