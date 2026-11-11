package org.qubership.integration.platform.ai.plan;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chat-facing view of an {@link ImplementationPlan}. Stored planText may keep internal digests;
 * this view omits hash metadata lines before streaming to the user.
 */
public final class ImplementationPlanChatView {

  /** Matches metadata lines like {@code Design input hash: <digest>} (any "* hash:" label). */
  private static final Pattern HASH_METADATA_LINE =
      Pattern.compile("(?i)^\\s*.+\\bhash:\\s*\\S.*$");

  private ImplementationPlanChatView() {}

  /**
   * Returns plan markdown safe for chat review: drops lines whose label ends with {@code hash:}.
   * Does not mutate the stored artifact.
   */
  public static String forChatReview(String planText) {
    if (planText == null || planText.isBlank()) {
      return planText == null ? "" : planText;
    }
    return planText
        .lines()
        .filter(line -> !HASH_METADATA_LINE.matcher(line).matches())
        .collect(Collectors.joining("\n"))
        .strip();
  }
}
