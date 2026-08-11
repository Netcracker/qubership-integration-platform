package org.qubership.integration.platform.ai.chat.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserIntentPatternsTest {

  @Test
  void matchesCreateChainIntentEnglish() {
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("implement the chain"));
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("build a chain"));
    assertTrue(UserIntentPatterns.matchesCreateChainIntent("create chain"));
  }

  @Test
  void matchesPlanQuestionEnglish() {
    assertTrue(UserIntentPatterns.matchesPlanQuestion("show graph"));
    assertTrue(UserIntentPatterns.matchesPlanQuestion("show json"));
    assertTrue(UserIntentPatterns.matchesPlanQuestion("how does the graph look"));
    assertTrue(UserIntentPatterns.matchesPlanQuestion("explain the plan"));
    assertFalse(UserIntentPatterns.matchesPlanQuestion("implement the chain"));
  }

  @Test
  void matchesImplementChainIntent() {
    assertTrue(UserIntentPatterns.matchesImplementChainIntent("implement the chain"));
    assertTrue(UserIntentPatterns.matchesImplementChainIntent("build the chain"));
    assertTrue(UserIntentPatterns.matchesImplementChainIntent("implement it"));
    assertFalse(UserIntentPatterns.matchesImplementChainIntent("create a Greetings chain"));
  }

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
  void shortPlanContinuationIgnoresBuriedAgreeInRichPrompt() {
    assertTrue(UserIntentPatterns.matchesShortPlanContinuation("Agree"));
    assertTrue(UserIntentPatterns.matchesShortPlanContinuation("Agree, proceed"));
    assertFalse(
        UserIntentPatterns.matchesShortPlanContinuation(
            """
            Execute the approved plan. Treat this as Agree / Execute plan.
            Then implement the chain with catalog companions.
            """));
  }

  @Test
  void doesNotMatchUnrelatedText() {
    assertFalse(UserIntentPatterns.matchesCreateChainIntent("hello world"));
  }

  @Test
  void doesNotTreatNegatedApprovalAsContinuation() {
    assertFalse(UserIntentPatterns.matchesShortPlanContinuation("do not proceed"));
    assertFalse(UserIntentPatterns.matchesShortPlanContinuation("don't implement yet"));
    assertFalse(UserIntentPatterns.matchesSpineRetryContinuation("do not retry"));
    assertFalse(UserIntentPatterns.matchesSpineRetryContinuation("don't try again"));
  }

  @Test
  void keepsExplicitApprovalAndRetryContinuations() {
    assertTrue(UserIntentPatterns.matchesShortPlanContinuation("Agree"));
    assertTrue(UserIntentPatterns.matchesSpineRetryContinuation("please retry"));
    assertTrue(UserIntentPatterns.matchesSpineRetryContinuation("try again"));
  }
}
