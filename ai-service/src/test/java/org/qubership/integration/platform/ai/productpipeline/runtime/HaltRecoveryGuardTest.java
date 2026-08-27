package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HaltRecoveryGuardTest {

  @Test
  void specGuardsAreNamedForTheDurableReasonAndTheCard() {
    Set<String> names =
        Arrays.stream(HaltRecoveryGuard.values()).map(Enum::name).collect(Collectors.toSet());
    assertTrue(names.contains("MAX_CAUSAL_REOPENS"));
    assertTrue(names.contains("OWNER_ALREADY_REOPENED"));
    assertTrue(names.contains("CATALOG_ALREADY_WRITTEN"));
    assertTrue(names.contains("NARRATIVE_EXPLANATION_BUDGET"));
    assertTrue(names.contains("MAX_SEMANTIC_REPAIRS"));
    assertTrue(names.contains("TECHNICAL_RETRY"));
    assertTrue(names.contains("REPEATED_FAILURE_THRESHOLD"));
    assertEquals(
        "This owner has already been reopened for this defect.",
        HaltRecoveryGuard.OWNER_ALREADY_REOPENED.cardSentence());
    assertTrue(
        HaltRecoveryGuard.remainingLine(new SemanticRecoveryState.RemainingAttempts(1, 2))
            .contains("Repairs remaining: 1"));
  }
}
