package org.qubership.integration.platform.ai.chat.chainplan;

import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects user intent to start a different chain (archive current plan) and extracts chain name
 * hints.
 */
public final class ChainPlanUserIntent {

  private static final Pattern CHAIN_NAME_AFTER_KEYWORD =
      Pattern.compile("(?ius)\\bchain\\s+['\"]?([\\p{L}\\p{N}][\\p{L}\\p{N}._-]*)['\"]?");

  private static final Pattern TEST_STYLE_NAME = Pattern.compile("(?ius)\\b(test\\d+)\\b");

  private ChainPlanUserIntent() {}

  public static boolean isNewChainRequest(String userText) {
    return UserIntentPatterns.matchesCreateChainIntent(userText);
  }

  public static Optional<String> extractChainNameCandidate(String userText) {
    if (userText == null || userText.isBlank()) {
      return Optional.empty();
    }
    Matcher m = CHAIN_NAME_AFTER_KEYWORD.matcher(userText);
    if (m.find()) {
      return Optional.of(m.group(1));
    }
    Matcher t = TEST_STYLE_NAME.matcher(userText);
    if (t.find()) {
      return Optional.of(t.group(1));
    }
    return Optional.empty();
  }

  /**
   * When the user starts a different chain while an active plan exists, the current plan should be
   * archived.
   */
  public static boolean shouldArchiveForNewChain(
      String userText, String currentChainName, Optional<String> mentionedChain) {
    if (mentionedChain.isEmpty()) {
      return false;
    }
    if (!isNewChainRequest(userText)) {
      return false;
    }
    return currentChainName == null || !mentionedChain.get().equalsIgnoreCase(currentChainName);
  }
}
