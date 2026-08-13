package org.qubership.integration.platform.ai.chat.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserIntentPatternsTest {

  /**
   * Survives ticket 10: naming a chain to build is how a conversation starts, before any run
   * exists and therefore before any gate. Nothing durable turns on the match — it picks which
   * scenario answers the turn.
   */
  @Test
  void createChainIntentSurvivesAsAConversationOpener() {
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("implement the chain"));
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("build a chain"));
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("create chain"));
  }

  /**
   * Survives ticket 10: plan questions are non-gate intents. Phase routing still routes them to
   * ASK_PLAN while a gate is open; approval no longer shares this matcher.
   */
  @Test
  void planQuestionSurvivesAsNonGateIntent() {
    assertTrue(UserIntentPatterns.matchesPlanQuestion("show graph"));
    assertTrue(UserIntentPatterns.matchesPlanQuestion("show json"));
    assertTrue(UserIntentPatterns.matchesPlanQuestion("how does the graph look"));
    assertTrue(UserIntentPatterns.matchesPlanQuestion("explain the plan"));
    assertFalse(UserIntentPatterns.matchesPlanQuestion("implement the chain"));
  }

  /**
   * Survives ticket 10: chain questions are non-gate intents. With chain context present, phase
   * routing still hard-routes them to ASK_CHAIN.
   */
  @Test
  void chainQuestionSurvivesAsNonGateIntent() {
    assertTrue(UserIntentPatterns.matchesChainQuestion("explain this chain"));
    assertTrue(UserIntentPatterns.matchesChainQuestion("what does this chain do"));
    assertTrue(UserIntentPatterns.matchesChainQuestion("how does this chain work"));
    assertFalse(UserIntentPatterns.matchesChainQuestion("implement the chain"));
  }

  /**
   * Survives ticket 10: modify-plan wording is a non-gate intent. Readers still ask to revise a
   * plan in free text; the router prompt and heuristics keep that path without treating it as
   * approval.
   */
  @Test
  void modifyPlanSurvivesAsNonGateIntent() {
    assertTrue(UserIntentPatterns.matchesModifyPlan("modify plan"));
    assertTrue(UserIntentPatterns.matchesModifyPlan("revise the plan"));
    assertTrue(UserIntentPatterns.matchesModifyPlan("change plan"));
    assertFalse(UserIntentPatterns.matchesModifyPlan("Agree"));
  }

  /**
   * Survives ticket 10: it serves chat scenarios outside the product CREATE branch, which has no
   * decision cards. Inside that branch nothing reaches the catalog through this matcher — writing
   * a chain is a command bound to the approved plan, and no wording substitutes for it.
   */
  @Test
  void implementChainIntentSurvivesOutsideTheProductCreateBranch() {
    assertTrue(UserIntentPatterns.matchesImplementChainIntent("implement the chain"));
    assertTrue(UserIntentPatterns.matchesImplementChainIntent("build the chain"));
    assertTrue(UserIntentPatterns.matchesImplementChainIntent("implement it"));
    assertFalse(UserIntentPatterns.matchesImplementChainIntent("create a Greetings chain"));
  }

  /**
   * Survives ticket 10 for the same reason, and keeps its compactness rule: a long message that
   * merely mentions building a chain is not a request to build one.
   */
  @Test
  void strongImplementIntentRequiresCompactMessage() {
    assertTrue(UserIntentPatterns.matchesStrongImplementChainIntent("implement the chain"));
    assertTrue(UserIntentPatterns.matchesStrongImplementChainIntent("implement it"));
    assertFalse(
        UserIntentPatterns.matchesStrongImplementChainIntent(
            """
            Execute the approved CIP design-first plan for Geographic Site.
            IDS and plan are already approved. Treat this as Agree / Execute plan.
            Build catalog companions and implement the chain against the catalog.
            """));
    // Weak find-based signal may still be true on the same rich text.
    assertTrue(
        UserIntentPatterns.matchesImplementChainIntent(
            """
            Execute the approved CIP design-first plan for Geographic Site.
            Build catalog companions and implement the chain against the catalog.
            """));
  }

  @Test
  void doesNotMatchUnrelatedText() {
    assertFalse(UserIntentPatterns.matchesCreateChainIntent("hello world"));
  }
}
