package org.qubership.integration.platform.ai.chat.intent;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared regex intent matchers for routing and plan lifecycle. */
public final class UserIntentPatterns {

  private static final Pattern MODIFY_PLAN =
      Pattern.compile("(?ius)\\b(modify(\\s+plan)?|change(\\s+plan)?|revise(\\s+plan)?)\\b");

  private static final Pattern IMPLEMENT_GATE_MODIFY_PLAN =
      Pattern.compile("(?ius)\\bmodify(\\s+plan)?\\b");

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

  private static final Pattern IMPLEMENT_GATE_AFFIRMATIVE =
      Pattern.compile("(?ius)\\b(yes|start\\s+implementation|proceed|implement)\\b");

  private static final Pattern SHORT_PLAN_CONTINUATION =
      Pattern.compile(
          "(?ius)^\\s*(agree|i\\s+confirm|confirm(ed)?|yes|ok|proceed|start\\s+implementation)\\s*[.!]?\\s*$");

  private static final Pattern IMPORT_PLAN_CONTINUATION =
      Pattern.compile("(?ius)^\\s*continue\\s*[.!]?\\s*$");

  private static final Pattern IMPORT_SPECIFICATION_COMMAND =
      Pattern.compile("(?ius)^\\s*import\\s+specification\\s*[.!]?\\s*$");

  private static final Pattern SPINE_RETRY_CONTINUATION =
      Pattern.compile("(?ius)\\b(retry|try\\s+again|rerun|re-?run)\\b");

  private static final Pattern NEGATED_CONTINUATION =
      Pattern.compile(
          "(?ius)\\b(do\\s+not|don't|dont|not)(?:\\s+\\w+){0,2}\\s+"
              + "(yes|proceed|implement|start\\s+implementation|retry|try\\s+again|rerun|re-?run)\\b");

  /**
   * Compact leading-intent budget for deterministic keyword routes (approve / implement). Rich
   * multi-sentence prompts stay weak signals for the capability ladder / LLM classifier.
   */
  static final int COMPACT_INTENT_MAX_CHARS = 160;

  private static final String UI_APPENDIX_DASHES = "\n---\n";
  private static final String UI_APPENDIX_CHAIN = "\n## Current Chain";

  private UserIntentPatterns() {}

  public static boolean matchesModifyPlan(String text) {
    return text != null && MODIFY_PLAN.matcher(text.trim()).find();
  }

  public static boolean matchesImplementGateModifyAnswer(String answer) {
    return answer != null && IMPLEMENT_GATE_MODIFY_PLAN.matcher(answer.trim()).find();
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
    // Multi-line prompts are design/execute briefs, not approve / implement button text.
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

  public static boolean matchesImplementGateAffirmative(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    String t = answer.trim();
    return IMPLEMENT_GATE_AFFIRMATIVE.matcher(t).find()
        && !NEGATED_CONTINUATION.matcher(t).find()
        && !matchesImplementGateModifyAnswer(t);
  }

  public static boolean matchesShortPlanContinuation(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    if (intent.isBlank()) {
      return false;
    }
    if (SHORT_PLAN_CONTINUATION.matcher(intent).matches()) {
      return true;
    }
    // Affirmative keywords (Agree / proceed / implement) are find-based — only honor them on
    // compact intents so rich "Agree / implement the chain" prompts do not auto-approve.
    return isCompactIntentMessage(text) && matchesImplementGateAffirmative(intent);
  }

  /** Short approval or explicit spine retry; not plan refinement. */
  public static boolean matchesSpineRetryContinuation(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    if (matchesModifyPlan(text)) {
      return false;
    }
    String intent = extractLeadingIntent(text);
    if (intent.isBlank() || NEGATED_CONTINUATION.matcher(intent).find()) {
      return false;
    }
    if (matchesShortPlanContinuation(text)) {
      return true;
    }
    return isCompactIntentMessage(text) && SPINE_RETRY_CONTINUATION.matcher(intent).find();
  }

  public static boolean matchesImportPlanContinuation(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    return IMPORT_PLAN_CONTINUATION.matcher(extractLeadingIntent(text)).matches();
  }

  public static boolean matchesImportSpecificationCommand(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    return IMPORT_SPECIFICATION_COMMAND.matcher(extractLeadingIntent(text)).matches();
  }

  public static boolean matchesExplicitImportRequest(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String intent = extractLeadingIntent(text).toLowerCase(Locale.ROOT);
    if (!intent.contains("import")) {
      return false;
    }
    return intent.contains("specification")
        || intent.contains("apihub")
        || intent.contains("catalog")
        || intent.contains("api hub");
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
