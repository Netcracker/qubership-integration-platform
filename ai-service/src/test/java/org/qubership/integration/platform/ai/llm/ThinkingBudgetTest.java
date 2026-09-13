package org.qubership.integration.platform.ai.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThinkingBudgetTest {

  @Test
  void anAbsentBudgetLeavesTheRequestUntouched() {
    assertTrue(ThinkingBudget.customParameters(Optional.empty()).isEmpty());
  }

  @Test
  void aZeroBudgetLeavesTheRequestUntouched() {
    assertTrue(ThinkingBudget.customParameters(Optional.of(0)).isEmpty());
  }

  @Test
  void aBudgetTravelsAsTheNativeThinkingField() {
    Map<String, Object> parameters = ThinkingBudget.customParameters(Optional.of(4096)).orElseThrow();

    assertEquals(
        Map.of("thinking", Map.of("type", "enabled", "budget_tokens", 4096)), parameters);
  }

  @Test
  void aBudgetBelowTheFloorIsRaisedToIt() {
    Map<String, Object> parameters = ThinkingBudget.customParameters(Optional.of(200)).orElseThrow();

    assertEquals(
        Map.of("thinking", Map.of("type", "enabled", "budget_tokens", ThinkingBudget.MIN_BUDGET_TOKENS)),
        parameters);
  }
}
