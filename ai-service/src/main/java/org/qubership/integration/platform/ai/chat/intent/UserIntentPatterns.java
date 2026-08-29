package org.qubership.integration.platform.ai.chat.intent;

import java.util.regex.Pattern;

/** Shared regex intent matchers for routing and plan lifecycle. */
public final class UserIntentPatterns {

  private static final Pattern MODIFY_PLAN =
      Pattern.compile("(?ius)\\b(modify(\\s+plan)?|change(\\s+plan)?|revise(\\s+plan)?)\\b");

  private static final Pattern CREATE_CHAIN_INTENT =
      Pattern.compile(
          "(?isU)\\b(create|build|make|implement|execute)"
              + "\\s+(?:(?:the|a)\\s+)?chain\\w*\\b");

  private static final Pattern IMPLEMENT_CHAIN_INTENT =
      Pattern.compile(
          "(?isU)\\b(implement|build|execute)"
              + "\\s+(?:(?:the|a)\\s+)?chain\\w*\\b");

  private static final Pattern IMPLEMENT_IT =
      Pattern.compile("(?ius)\\b(implement\\s+it|build\\s+it)\\b");

  private static final Pattern PLAN_QUESTION =
      Pattern.compile(
          "(?isU)\\b("
              + "show\\s+(the\\s+)?(graph|plan|json|tree|script)|"
              + "display\\s+(the\\s+)?(graph|plan|json|tree|script)|"
              + "how\\s+does\\s+the\\s+graph\\s+look|"
              + "explain\\s+(the\\s+)?plan|why\\s+try[- ]?catch"
              + ")\\b");

  private static final Pattern CHAIN_QUESTION =
      Pattern.compile(
          "(?isU)\\b("
              + "explain\\s+(this\\s+)?chain|what\\s+does\\s+(this\\s+)?chain\\s+do|"
              + "how\\s+does\\s+(this\\s+)?chain\\s+work|what\\s+is\\s+(this\\s+)?chain\\s+for|"
              + "describe\\s+(this\\s+)?chain|tell\\s+me\\s+about\\s+(this\\s+)?chain|"
              + "show\\s+(the\\s+)?(graph|json|tree|script)"
              + ")\\b");

  private static final Pattern SNAPSHOT_INTENT =
      Pattern.compile(
          "(?isU)\\b("
              + "(take|create|make)\\s+(a\\s+)?snapshot|"
              + "snapshot\\s+(this\\s+|the\\s+)?chain"
              + ")\\b");

  private static final Pattern DEPLOY_INTENT =
      Pattern.compile("(?isU)\\b(deploy\\s+(this\\s+|the\\s+)?chain|deploy\\s+it)\\b");

  /**
   * Compact leading-intent budget for deterministic keyword routes (implement). Rich multi-sentence
   * prompts stay weak signals for the capability ladder / LLM classifier.
   */
  static final int COMPACT_INTENT_MAX_CHARS = 160;

  private static final String UI_APPENDIX_DASHES = "\n---\n";
  private static final String UI_APPENDIX_CHAIN = "\n## Current Chain";

  private UserIntentPatterns() {}

  public static boolean matchesModifyPlan(String text) {
    return text != null && MODIFY_PLAN.matcher(text.trim()).find();
  }

  public static boolean matchesCreateChainIntent(String text) {
    return text != null && CREATE_CHAIN_INTENT.matcher(text.trim()).find();
  }

  /** Weak find-based signal; may match inside long design/execute prompts. */
  public static boolean matchesImplementChainIntent(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String t = text.trim();
    return IMPLEMENT_CHAIN_INTENT.matcher(t).find() || IMPLEMENT_IT.matcher(t).find();
  }

  /**
   * Strong implement route: compact leading intent only. Long prompts that merely mention
   * "implement the chain" must not hard-route; the capability ladder / LLM own those.
   */
  public static boolean matchesStrongImplementChainIntent(String text) {
    if (!isCompactIntentMessage(text)) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    return IMPLEMENT_CHAIN_INTENT.matcher(intent).find() || IMPLEMENT_IT.matcher(intent).find();
  }

  /** True when leading intent is short enough for deterministic keyword routing. */
  public static boolean isCompactIntentMessage(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    if (intent.isBlank()) {
      return false;
    }
    // Multi-line prompts are design/execute briefs, not implement button text.
    if (intent.indexOf('\n') >= 0) {
      return false;
    }
    return intent.length() <= COMPACT_INTENT_MAX_CHARS;
  }

  public static boolean matchesPlanQuestion(String text) {
    return text != null && PLAN_QUESTION.matcher(text.trim()).find();
  }

  public static boolean matchesChainQuestion(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    return CHAIN_QUESTION.matcher(intent).find();
  }

  /** Compact snapshot phrasing. Phase routing checks this before the ASK_CHAIN shortcut. */
  public static boolean matchesSnapshotIntent(String text) {
    if (!isCompactIntentMessage(text)) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    return SNAPSHOT_INTENT.matcher(intent).find();
  }

  /**
   * Compact deploy phrasing. Phase routing checks this before the ASK_CHAIN shortcut when a chain
   * is open. Snapshot wording is a separate matcher so it is not stolen.
   */
  public static boolean matchesDeployIntent(String text) {
    if (!isCompactIntentMessage(text)) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    return DEPLOY_INTENT.matcher(intent).find();
  }

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
