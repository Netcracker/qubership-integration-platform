package org.qubership.integration.platform.ai.chat.guardrail;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GuardrailShortReplyPolicyTest {

  @Test
  void numberedAnswerAllowedWhenActivePlan() {
    boolean ok =
        GuardrailShortReplyPolicy.allowThrough(
            "1. plain text 2. No",
            "Assistant: Choose format?",
            Optional.of(
                new ActiveChainPlanSnapshot(
                    "p",
                    "t",
                    null,
                    null,
                    new ChainImplementationPlan(),
                    Instant.now(),
                    List.of(),
                    List.of())));
    assertTrue(ok);
  }

  @Test
  void shortAnswerAllowedWhenTranscriptQipish() {
    boolean ok =
        GuardrailShortReplyPolicy.allowThrough(
            "1. foo 2. bar", "User: build chain X\n\nAssistant: Questions?\n", Optional.empty());
    assertTrue(ok);
  }
}
