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

  private static final Pattern SHORT_PLAN_CONTINUATION =
      Pattern.compile(
          "(?ius)^\\s*(agree|i\\s+confirm|confirm(ed)?|yes|ok|proceed|start\\s+implementation)\\s*[.!]?\\s*$");

  private static final String UI_APPENDIX_DASHES = "\n---\n";
  private static final String UI_APPENDIX_CHAIN = "\n## Current Chain";

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

  /**
   * Short approval/continuation messages (e.g. {@code Agree}, {@code i confirm}) that must not
   * route to IMPORT_SPECIFICATION when a plan exists or import already completed.
   */
  public static boolean matchesShortPlanContinuation(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    if (intent.isBlank()) {
      return false;
    }
    return SHORT_PLAN_CONTINUATION.matcher(intent).matches()
        || matchesImplementGateAffirmative(intent);
  }

  /** Explicit user request to import a specification (not a generic confirmation). */
  public static boolean matchesExplicitImportRequest(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String intent = extractLeadingIntent(text).toLowerCase(java.util.Locale.ROOT);
    if (!intent.contains("import")) {
      return false;
    }
    return intent.contains("specification")
        || intent.contains("apihub")
        || intent.contains("catalog")
        || intent.contains("api hub");
  }

  /** User intent before UI attachment appendix (same cut as plan approval gate). */
  public static String extractLeadingIntent(String userText) {
    if (userText == null || userText.isBlank()) {
      return "";
    }
    String t = userText.trim();
    int dash = t.indexOf(UI_APPENDIX_DASHES);
    int chain = t.indexOf(UI_APPENDIX_CHAIN);
    int cut = -1;
    if (dash >= 0 && chain >= 0) {
      cut = Math.min(dash, chain);
    } else if (dash >= 0) {
      cut = dash;
    } else if (chain >= 0) {
      cut = chain;
    }
    return cut >= 0 ? t.substring(0, cut).trim() : t;
  }
}
