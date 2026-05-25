package org.qubership.integration.platform.ai.chat.guardrail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transcript helpers for deterministic guardrail (no attachment body). */
final class GuardrailTranscriptHeuristics {

  private static final Pattern QIPISH_TRANSCRIPT =
      Pattern.compile(
          "(?ius)\\b(qip|chain|integration|apihub|catalog|http-trigger|service-call|design"
              + " document|ids)\\b");

  private static final Pattern ASSISTANT_OPEN_PROMPT =
      Pattern.compile("(?ius)\\b(provide|specify|attach|share|send|upload|details? of|design)\\b");

  private static final Pattern LAST_ASSISTANT_BLOCK =
      Pattern.compile("(?s)Assistant:\\s*(.*?)(?:\\n\\n(?:User:|System:)|\\z)");

  private GuardrailTranscriptHeuristics() {}

  static boolean isTranscriptQipish(String recentTranscript) {
    if (recentTranscript == null || recentTranscript.isBlank()) {
      return false;
    }
    return QIPISH_TRANSCRIPT.matcher(recentTranscript).find();
  }

  static boolean isLastAssistantOpenPrompt(String recentTranscript) {
    String lastAssistant = extractLastAssistantBlock(recentTranscript);
    if (lastAssistant.isBlank()) {
      return false;
    }
    return ASSISTANT_OPEN_PROMPT.matcher(lastAssistant).find();
  }

  static String extractLastAssistantBlock(String recentTranscript) {
    if (recentTranscript == null || recentTranscript.isBlank()) {
      return "";
    }
    Matcher m = LAST_ASSISTANT_BLOCK.matcher(recentTranscript);
    String last = "";
    while (m.find()) {
      last = m.group(1).trim();
    }
    return last;
  }
}
