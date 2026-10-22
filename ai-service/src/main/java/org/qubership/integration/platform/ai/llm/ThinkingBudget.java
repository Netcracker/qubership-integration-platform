package org.qubership.integration.platform.ai.llm;

import java.util.Map;
import java.util.Optional;

/**
 * Extended-thinking budget for Claude models reached through their OpenAI-compatible endpoint.
 *
 * <p>That endpoint ignores {@code reasoning_effort}, and Claude 5 decides on its own how long to
 * think, which makes a turn slow without an upper bound. The native {@code thinking} field is the
 * only knob it honors, so the client sends it as an extra body parameter. An absent budget leaves
 * the field out and keeps the model's own pacing.
 *
 * <p>The completion cap must stay above the budget: thinking and the answer share it.
 */
final class ThinkingBudget {

  /** Smallest budget Claude accepts. Anything lower is a configuration mistake. */
  static final int MIN_BUDGET_TOKENS = 1024;

  private ThinkingBudget() {}

  static Optional<Map<String, Object>> customParameters(Optional<Integer> budgetTokens) {
    return budgetTokens
        .filter(budget -> budget > 0)
        .map(
            budget ->
                Map.of(
                    "thinking",
                    Map.of("type", "enabled", "budget_tokens", Math.max(budget, MIN_BUDGET_TOKENS))));
  }
}
