package org.qubership.integration.platform.ai.chat.guardrail;

import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.llm.routing.RouterHeuristics;

import java.util.Optional;

/** Deterministic guardrail bypass for short continuations that models often mishandle. */
public final class GuardrailShortReplyPolicy {

  private GuardrailShortReplyPolicy() {}

  public static boolean allowThrough(
      String message, String recentTranscript, Optional<ActiveChainPlanSnapshot> activePlan) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String transcript = recentTranscript != null ? recentTranscript : "";

    if (activePlan.isPresent() && RouterHeuristics.isShortConfirmationOrChecklist(message)) {
      return true;
    }

    if (RouterHeuristics.isShortConfirmationOrChecklist(message)
        && GuardrailTranscriptHeuristics.isTranscriptQipish(transcript)) {
      return true;
    }

    return false;
  }
}
