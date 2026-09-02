package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class MappingGapCoverageTest {

  @Test
  void rockyBriefWithNoIntentsHasTwoUncoveredTransitions() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.rockyBrief();
    List<Transition> uncovered = MappingGapCoverage.uncovered(brief);
    assertEquals(2, uncovered.size());
    assertTrue(MappingGapCoverage.shouldAsk(uncovered, null, "sha-1"));
  }

  @Test
  void emptyFlowDoesNotAsk() {
    assertFalse(
        MappingGapCoverage.shouldAsk(
            MappingGapCoverage.uncovered(ChainSemanticCaptureFixtures.approvedBrief()),
            null,
            "sha-1"));
  }

  @Test
  void confirmationMatchesSubsetOfStoredUncovered() {
    MappingGapPassThroughConfirmation confirmation =
        MappingGapPassThroughConfirmation.parse(
                """
                {"action":"pass_through","briefSha":"abc",\
                "uncovered":[{"sourceRef":"a","targetRef":"b"},{"sourceRef":"c","targetRef":"d"}]}
                """)
            .orElseThrow();
    List<Transition> current = List.of(new Transition("a", "b"));
    assertTrue(confirmation.matches("abc", current));
    assertFalse(confirmation.matches("other", current));
    assertFalse(confirmation.matches("abc", List.of(new Transition("a", "b"), new Transition("x", "y"))));
  }
}
