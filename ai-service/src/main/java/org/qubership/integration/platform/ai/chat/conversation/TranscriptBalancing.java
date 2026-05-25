package org.qubership.integration.platform.ai.chat.conversation;

/** Head+tail preserving truncation and transcript budgeting for router/guardrail prompts. */
public final class TranscriptBalancing {

  private static final String TRUNC_MARK = "\n… [truncated middle] …\n";

  private TranscriptBalancing() {}

  /**
   * Keeps the last {@code maxChars} characters (trimmed); empty when {@code s} is null or blank.
   */
  public static String tailOnly(String s, int maxChars) {
    if (s == null || s.isBlank() || maxChars <= 0) {
      return "";
    }
    String t = s.trim();
    if (t.length() <= maxChars) {
      return t;
    }
    return t.substring(t.length() - maxChars);
  }

  /** Keeps beginning and end when {@code s} exceeds {@code maxChars}. */
  public static String truncateBalanced(String s, int maxChars) {
    if (s == null) {
      return "";
    }
    if (maxChars < 80 || s.length() <= maxChars) {
      return s;
    }
    int innerBudget = maxChars - TRUNC_MARK.length();
    int half = innerBudget / 2;
    return s.substring(0, half) + TRUNC_MARK + s.substring(s.length() - half);
  }

  /**
   * Drops whole labeled messages from the start until the transcript fits {@code maxTotalChars}, or
   * only the newest message remains.
   */
  public static int trimStartIndexForBudget(
      java.util.List<String> labeledBodies, int maxTotalChars, int minimumTailMessages) {
    if (labeledBodies.isEmpty()) {
      return 0;
    }
    int start = 0;
    while (start < labeledBodies.size() - minimumTailMessages) {
      StringBuilder sb = new StringBuilder();
      for (int i = start; i < labeledBodies.size(); i++) {
        sb.append(labeledBodies.get(i));
      }
      if (sb.length() <= maxTotalChars) {
        return start;
      }
      start++;
    }
    return start;
  }
}
