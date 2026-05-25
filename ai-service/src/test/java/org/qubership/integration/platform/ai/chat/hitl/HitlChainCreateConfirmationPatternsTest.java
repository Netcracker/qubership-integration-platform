package org.qubership.integration.platform.ai.chat.hitl;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@link HitlTool} chain-create / affirmative patterns — update both when changing
 * detection logic.
 */
class HitlChainCreateConfirmationPatternsTest {

  private static final Pattern CHAIN_CREATE_HITL_QUESTION =
      Pattern.compile(
          "(?ius)create\\s+(the\\s+)?new\\s+chain|\\bcreateChain\\b|proceeding\\s+to\\s+create");

  private static final Pattern AFFIRMATIVE_ANSWER =
      Pattern.compile("(?ius)\\b(agree|yes|yep|yeah|ok|confirm|confirmed|proceed|\\+)\\b");

  @Test
  void chainCreateQuestionMatchesImplementFlowCopy() {
    assertTrue(
        CHAIN_CREATE_HITL_QUESTION
            .matcher(
                "Proceeding to create the new chain 'Condition test 2' with the specified"
                    + " elements.")
            .find());
    assertTrue(CHAIN_CREATE_HITL_QUESTION.matcher("Please call createChain next.").find());
    assertFalse(CHAIN_CREATE_HITL_QUESTION.matcher("Which API version should we use?").find());
  }

  @Test
  void affirmativeAnswerMatchesHitlUiAnswers() {
    assertTrue(AFFIRMATIVE_ANSWER.matcher("Agree").find());
    assertTrue(AFFIRMATIVE_ANSWER.matcher("  agree! ").find());
    assertTrue(AFFIRMATIVE_ANSWER.matcher("yes, proceed").find());
    assertFalse(AFFIRMATIVE_ANSWER.matcher("Cancel").find());
  }
}
