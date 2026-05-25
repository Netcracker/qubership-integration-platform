package org.qubership.integration.platform.ai.chat.hitl;

import java.util.regex.Pattern;

/**
 * Rejects {@code requestConfirmation} questions that imply catalog execution or a finished plan
 * before {@link org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService} has
 * captured a {@code ChainImplementationPlan}, or before the user approved it (CREATE_CHAIN_PLAN
 * role).
 */
public final class HitlPlanningPhaseGuard {

  private static final Pattern FORBIDDEN_BEFORE_APPROVED_PLAN =
      Pattern.compile(
          "(?ius)(\\bexecute\\s+the\\s+plan\\b|\\bexecute\\s+plan\\b"
              + "|\\bimplement(ing)?\\s+the\\s+plan\\b"
              + "|\\bproceed\\s+with\\s+creating\\s+(the\\s+)?chain\\b"
              + "|\\bproceed\\s+with\\s+implementing\\s+(the\\s+)?plan\\b"
              + "|\\bcreating\\s+(the\\s+)?chain\\s+and\\s+implementing\\b"
              + "|\\bcreate\\s+the\\s+chain\\s+and\\s+implement\\b"
              + "|\\bcreateChain\\b|\\bproceeding\\s+to\\s+create\\b)");

  private HitlPlanningPhaseGuard() {}

  /**
   * @return non-null rejection text for the LLM when the call must be blocked; {@code null} when
   *     allowed.
   */
  public static String rejectOrNull(
      String question, boolean hasSubstantivePlan, boolean planApproved) {
    if (question == null || question.isBlank()) {
      return null;
    }
    if (!FORBIDDEN_BEFORE_APPROVED_PLAN.matcher(question).find()) {
      return null;
    }
    if (hasSubstantivePlan && planApproved) {
      return null;
    }
    return "Error: plan execution HITL is not allowed here. The ChainImplementationPlan has not"
               + " been captured from your assistant reply and/or approved yet. In"
               + " CREATE_CHAIN_PLAN: finish Phase A (service/operation binding), emit a complete"
               + " fenced ChainImplementationPlan JSON block in your assistant message (server"
               + " captures activePlan automatically), then call requestConfirmation with options"
               + " \"Agree,Modify plan\". For"
               + " ambiguous catalog systems, use Phase A HITL: list candidates (name, type, id"
               + " prefix) and options such as \"Use <system name>,I will paste"
               + " integrationSystemId,Cancel\" — not execute/implement/createChain or Yes/No-only"
               + " execution prompts.";
  }
}
