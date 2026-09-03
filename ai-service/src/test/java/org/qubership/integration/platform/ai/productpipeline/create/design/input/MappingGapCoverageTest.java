package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class MappingGapCoverageTest {

  @Test
  void rockyBriefWithNoIntentsHasTwoUncoveredTransitions() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.rockyBrief();
    List<Transition> uncovered = MappingGapCoverage.uncovered(brief);
    assertEquals(2, uncovered.size());
    assertTrue(MappingGapCoverage.shouldAsk(uncovered));
    assertEquals(MappingGapCoverage.State.UNCOVERED, MappingGapCoverage.state(brief, uncovered.getFirst()));
  }

  @Test
  void emptyFlowDoesNotAsk() {
    assertFalse(
        MappingGapCoverage.shouldAsk(
            MappingGapCoverage.uncovered(ChainSemanticCaptureFixtures.approvedBrief())));
  }

  @Test
  void emptyIntentsWithNoSkipLeaveEveryHopUncovered() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.rockyBrief();
    assertTrue(brief.mappingIntents().isEmpty());
    assertTrue(brief.skippedTransitions().isEmpty());
    assertEquals(2, MappingGapCoverage.uncovered(brief).size());
  }

  @Test
  void intentWithAtLeastOneRuleIsMapped() {
    RequirementBrief brief =
        ChainSemanticCaptureFixtures.rockyBrief()
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-task-start-to-create-task",
                        "task-start",
                        MappingPort.OUTPUT,
                        "create-task",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("name", "Subject", null)))));
    List<Transition> uncovered = MappingGapCoverage.uncovered(brief);
    assertEquals(1, uncovered.size());
    assertEquals("create-task", uncovered.getFirst().sourceInteractionId());
    assertEquals("task-result", uncovered.getFirst().targetInteractionId());
    assertEquals(
        MappingGapCoverage.State.MAPPED,
        MappingGapCoverage.state(brief, new Transition("task-start", "create-task")));
  }

  @Test
  void emptyRuleIntentIsNotMapped() {
    RequirementBrief brief =
        ChainSemanticCaptureFixtures.rockyBrief()
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-task-start-to-create-task",
                        "task-start",
                        MappingPort.OUTPUT,
                        "create-task",
                        MappingPort.REQUEST,
                        List.of())));
    assertEquals(2, MappingGapCoverage.uncovered(brief).size());
  }

  @Test
  void skipRecordsRemoveHopsFromUncoveredWithoutMappingRows() {
    RequirementBrief skipped =
        MappingGapCoverage.skipUncovered(ChainSemanticCaptureFixtures.rockyBrief());
    assertTrue(skipped.mappingIntents().isEmpty());
    assertEquals(2, skipped.skippedTransitions().size());
    assertTrue(MappingGapCoverage.uncovered(skipped).isEmpty());
    assertFalse(MappingGapCoverage.shouldAsk(MappingGapCoverage.uncovered(skipped)));
    assertEquals(
        MappingGapCoverage.State.SKIPPED,
        MappingGapCoverage.state(skipped, new Transition("task-start", "create-task")));
  }

  @Test
  void skipUncoveredLeavesMappedHopsAndSkipsOnlyTheRemainder() {
    RequirementBrief mappedFirst =
        ChainSemanticCaptureFixtures.rockyBrief()
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-task-start-to-create-task",
                        "task-start",
                        MappingPort.OUTPUT,
                        "create-task",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("name", "Subject", null)))));
    RequirementBrief afterSkip = MappingGapCoverage.skipUncovered(mappedFirst);
    assertEquals(1, afterSkip.mappingIntents().size());
    assertEquals(1, afterSkip.skippedTransitions().size());
    assertEquals("create-task", afterSkip.skippedTransitions().getFirst().sourceInteractionId());
    assertEquals("task-result", afterSkip.skippedTransitions().getFirst().targetInteractionId());
    assertTrue(MappingGapCoverage.uncovered(afterSkip).isEmpty());
  }

  @Test
  void confirmationDoesNotCoverUncoveredHops() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.rockyBrief();
    List<Transition> uncovered = MappingGapCoverage.uncovered(brief);
    MappingGapPassThroughConfirmation confirmation =
        new MappingGapPassThroughConfirmation(
            "abc",
            List.of(
                new MappingGapPassThroughConfirmation.TransitionRef("task-start", "create-task"),
                new MappingGapPassThroughConfirmation.TransitionRef("create-task", "task-result")));
    assertTrue(confirmation.matches("abc", uncovered));
    assertTrue(MappingGapCoverage.shouldAsk(uncovered, confirmation, "abc"));
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
