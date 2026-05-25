package org.qubership.integration.platform.ai.chat.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationPlanningDiaryDecisionTest {

  @Test
  void recordDecisionAppearsInAppendix() {
    ConversationPlanningDiaryService svc = new ConversationPlanningDiaryService();
    String conv = "decision-test";
    svc.recordDecision(conv, "User approved plan v2");

    String appendix = svc.formatAppendix(conv);
    assertTrue(appendix.contains("Decision log"));
    assertTrue(appendix.contains("User approved plan v2"));
  }
}
