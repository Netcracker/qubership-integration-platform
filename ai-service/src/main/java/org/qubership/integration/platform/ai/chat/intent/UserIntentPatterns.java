package org.qubership.integration.platform.ai.chat.intent;

import java.util.regex.Pattern;

/** Shared regex intent matchers for routing, gates, and plan lifecycle. */
public final class UserIntentPatterns {

  private static final Pattern MODIFY_PLAN =
      Pattern.compile("(?ius)\\b(modify(\\s+plan)?|change(\\s+plan)?|revise(\\s+plan)?)\\b");

  private static final Pattern IMPLEMENT_GATE_MODIFY_PLAN =
      Pattern.compile("(?ius)\\bmodify(\\s+plan)?\\b");

  private static final Pattern CREATE_CHAIN_INTENT =
      Pattern.compile(
          "(?ius)\\b(create|build|make|implement|execute)\\s+(?:(?:the|a)\\s+)?chain\\b");

  private static final Pattern IMPLEMENT_GATE_AFFIRMATIVE =
      Pattern.compile("(?ius)\\b(yes|start\\s+implementation|proceed|implement)\\b");

  private UserIntentPatterns() {}

  /** Routing and plan-approval veto when user wants to revise the plan. */
  public static boolean matchesModifyPlan(String text) {
    return text != null && MODIFY_PLAN.matcher(text.trim()).find();
  }

  /** Gate 2 HITL answer: user chose to modify rather than start implementation. */
  public static boolean matchesImplementGateModifyAnswer(String answer) {
    return answer != null && IMPLEMENT_GATE_MODIFY_PLAN.matcher(answer.trim()).find();
  }

  /** Explicit create/build/implement chain wording. */
  public static boolean matchesCreateChainIntent(String text) {
    return text != null && CREATE_CHAIN_INTENT.matcher(text.trim()).find();
  }

  /** Gate 2 HITL answer: user agreed to start implementation. */
  public static boolean matchesImplementGateAffirmative(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    String t = answer.trim();
    return IMPLEMENT_GATE_AFFIRMATIVE.matcher(t).find() && !matchesImplementGateModifyAnswer(t);
  }
}
