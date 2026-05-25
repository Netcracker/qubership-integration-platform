package org.qubership.integration.platform.ai.chat.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserIntentPatternsTest {

  @Test
  void matchesModifyPlanRoutingAndApprovalPhrases() {
    assertTrue(UserIntentPatterns.matchesModifyPlan("Modify plan"));
    assertTrue(UserIntentPatterns.matchesModifyPlan("Please change plan"));
    assertTrue(UserIntentPatterns.matchesModifyPlan("revise plan"));
    assertFalse(UserIntentPatterns.matchesModifyPlan("yes"));
  }

  @Test
  void matchesCreateChainIntentBroaderArticle() {
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("implement the chain"));
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("build a chain"));
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("create chain"));
  }

  @Test
  void implementGateAnswers() {
    assertTrue(UserIntentPatterns.matchesImplementGateAffirmative("Yes"));
    assertTrue(UserIntentPatterns.matchesImplementGateAffirmative("start implementation"));
    assertFalse(UserIntentPatterns.matchesImplementGateAffirmative("Modify plan"));
    assertTrue(UserIntentPatterns.matchesImplementGateModifyAnswer("Modify plan"));
    assertFalse(UserIntentPatterns.matchesImplementGateModifyAnswer("change plan"));
  }
}
