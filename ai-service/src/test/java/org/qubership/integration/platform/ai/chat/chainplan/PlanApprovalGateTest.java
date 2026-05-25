package org.qubership.integration.platform.ai.chat.chainplan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanApprovalGateTest {

  @Test
  void extractIntentPlainText() {
    assertEquals("I agree with the plan", PlanApprovalGate.extractIntent("I agree with the plan"));
  }

  @Test
  void extractIntentBeforeDashSeparator() {
    String raw = "Yes, build the chain\n---\n## Current Chain: Foo";
    assertEquals("Yes, build the chain", PlanApprovalGate.extractIntent(raw));
  }

  @Test
  void extractIntentBeforeCurrentChainMarker() {
    String raw = "I confirm\n## Current Chain: Bar (ID: abc)";
    assertEquals("I confirm", PlanApprovalGate.extractIntent(raw));
  }

  @Test
  void extractIntentTrimsWhitespace() {
    assertEquals("approve the plan", PlanApprovalGate.extractIntent("  approve the plan  "));
  }

  @Test
  void extractIntentBlank() {
    assertTrue(PlanApprovalGate.extractIntent("").isEmpty());
    assertTrue(PlanApprovalGate.extractIntent("   ").isEmpty());
  }

  @Test
  void vetoesModifyPlan() {
    assertTrue(PlanApprovalGate.vetoesApproval("modify plan"));
    assertTrue(PlanApprovalGate.vetoesApproval("Please change plan"));
  }

  @Test
  void vetoesAllowsApprovalIntent() {
    assertFalse(PlanApprovalGate.vetoesApproval("I agree with the plan"));
    assertFalse(PlanApprovalGate.vetoesApproval("approve the plan"));
    assertFalse(PlanApprovalGate.vetoesApproval("Yes, build the chain"));
  }

  @Test
  void vetoesBlank() {
    assertTrue(PlanApprovalGate.vetoesApproval(""));
    assertTrue(PlanApprovalGate.vetoesApproval("   "));
  }
}
