package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.MappingQueryAnswer.RuleFact;
import org.qubership.integration.platform.ai.plan.MappingQueryAnswer.TransitionFact;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class MappingQueryLookupTest {

  @Test
  void lookupByTransitionReturnsStoredRulesAndStatuses() {
    RequirementBrief brief = mappedRockyBrief();
    MappingQuerySelector selector =
        new MappingQuerySelector(
            null,
            "task-start",
            "create-task",
            null,
            null,
            false,
            MappingQuerySelector.Coverage.ANY);

    MappingQueryAnswer answer = MappingQueryLookup.answer(brief, selector);

    assertTrue(answer.matchFound());
    assertEquals("en", answer.language());
    assertEquals(2, answer.rules().size());
    RuleFact subject = ruleWriting(answer, "$.Subject");
    assertEquals("map-task-start-to-create-task", subject.mappingIntentId());
    assertEquals("task-start", subject.sourceRef());
    assertEquals("create-task", subject.targetRef());
    assertEquals("$.name", subject.sourcePath());
    assertEquals("$.Subject", subject.targetPath());
    assertEquals(MappingRuleStatus.USER_DEFINED, subject.status());
    assertTrue(answer.rendered().contains("map-task-start-to-create-task"));
    assertTrue(answer.rendered().contains("$.Subject"));
    assertTrue(answer.rendered().contains("$.name"));
    assertTrue(answer.rendered().contains("USER_DEFINED"));
    assertFalse(answer.transitions().stream().anyMatch(TransitionFact::passThrough));
  }

  @Test
  void lookupByTargetPathReturnsTheStoredWriter() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            mappedRockyBrief(),
            new MappingQuerySelector(
                null, null, null, null, "Subject", false, MappingQuerySelector.Coverage.ANY));

    assertTrue(answer.matchFound());
    assertEquals(1, answer.rules().size());
    RuleFact writer = answer.rules().getFirst();
    assertEquals("map-task-start-to-create-task", writer.mappingIntentId());
    assertEquals("$.name", writer.sourcePath());
    assertEquals("$.Subject", writer.targetPath());
    assertTrue(answer.rendered().contains("$.Subject"));
  }

  @Test
  void lookupBySourcePathReturnsWhereTheFieldIsUsed() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            mappedRockyBrief(),
            new MappingQuerySelector(
                null, null, null, "name", null, false, MappingQuerySelector.Coverage.ANY));

    assertTrue(answer.matchFound());
    assertEquals(1, answer.rules().size());
    assertEquals("$.name", answer.rules().getFirst().sourcePath());
    assertEquals("$.Subject", answer.rules().getFirst().targetPath());
  }

  @Test
  void lookupByMappingIntentIdReturnsThatIntent() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            mappedRockyBrief(),
            new MappingQuerySelector(
                "map-create-task-to-task-result",
                null,
                null,
                null,
                null,
                false,
                MappingQuerySelector.Coverage.ANY));

    assertTrue(answer.matchFound());
    assertEquals(1, answer.rules().size());
    assertEquals("map-create-task-to-task-result", answer.rules().getFirst().mappingIntentId());
    assertEquals("$.commandType", answer.rules().getFirst().targetPath());
    assertEquals("Set to completeTask.", answer.rules().getFirst().expression());
  }

  @Test
  void lookupUnresolvedTargetsReturnsStoredUnresolvedPaths() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            briefWithUnresolvedStatus(), MappingQuerySelector.unresolvedTargets());

    assertTrue(answer.matchFound());
    assertEquals(List.of("$.Status"), answer.unresolvedTargetPaths());
    assertEquals("$.Status", answer.rules().getFirst().targetPath());
    assertEquals(MappingRuleStatus.UNRESOLVED, answer.rules().getFirst().status());
    assertTrue(answer.rendered().contains("$.Status"));
    assertTrue(answer.rendered().contains("UNRESOLVED"));
  }

  @Test
  void lookupMappedCoverageListsTransitionsThatHaveIntents() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            requestOnlyRockyBrief(),
            new MappingQuerySelector(
                null, null, null, null, null, false, MappingQuerySelector.Coverage.MAPPED));

    assertTrue(answer.matchFound());
    assertEquals(1, answer.transitions().size());
    TransitionFact mapped = answer.transitions().getFirst();
    assertEquals("task-start", mapped.sourceRef());
    assertEquals("create-task", mapped.targetRef());
    assertEquals("map-task-start-to-create-task", mapped.mappingIntentId());
    assertFalse(mapped.passThrough());
  }

  @Test
  void lookupPassThroughCoverageListsExplicitlySkippedTransitions() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            requestMappedAndResponseSkippedBrief(),
            new MappingQuerySelector(
                null,
                null,
                null,
                null,
                null,
                false,
                MappingQuerySelector.Coverage.PASS_THROUGH));

    assertTrue(answer.matchFound());
    assertEquals(1, answer.transitions().size());
    TransitionFact passThrough = answer.transitions().getFirst();
    assertEquals("create-task", passThrough.sourceRef());
    assertEquals("task-result", passThrough.targetRef());
    assertTrue(passThrough.passThrough());
    assertTrue(answer.rendered().contains("create-task"));
    assertTrue(answer.rendered().contains("task-result"));
    assertTrue(answer.rendered().toLowerCase().contains("pass-through"));
  }

  @Test
  void lookupOfASkippedBoundaryStatesPassThrough() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            requestMappedAndResponseSkippedBrief(),
            new MappingQuerySelector(
                null,
                "create-task",
                "task-result",
                null,
                null,
                false,
                MappingQuerySelector.Coverage.ANY));

    assertTrue(answer.matchFound());
    assertTrue(answer.rules().isEmpty());
    assertEquals(1, answer.transitions().size());
    assertTrue(answer.transitions().getFirst().passThrough());
    assertTrue(answer.rendered().toLowerCase().contains("pass-through"));
  }

  @Test
  void lookupDoesNotReportAnUncoveredBoundaryAsPassThrough() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            requestOnlyRockyBrief(),
            new MappingQuerySelector(
                null,
                "create-task",
                "task-result",
                null,
                null,
                false,
                MappingQuerySelector.Coverage.PASS_THROUGH));

    assertFalse(answer.matchFound());
    assertTrue(answer.transitions().isEmpty());
  }

  @Test
  void lookupWithNoMatchStatesThatExplicitly() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            mappedRockyBrief(),
            new MappingQuerySelector(
                null, null, null, null, "MissingField", false, MappingQuerySelector.Coverage.ANY));

    assertFalse(answer.matchFound());
    assertTrue(answer.rules().isEmpty());
    assertTrue(answer.rendered().toLowerCase().contains("no mapping"));
  }

  @Test
  void paraphraseQuestionRendersEnglishWhileKeepingIdentifiersAndPathsVerbatim() {
    MappingQueryAnswer answer =
        MappingQueryLookup.answer(
            mappedRockyBrief(),
            new MappingQuerySelector(
                null, null, null, null, "Subject", false, MappingQuerySelector.Coverage.ANY));

    assertEquals("en", answer.language());
    assertTrue(answer.rendered().contains(" writes "));
    assertTrue(answer.rendered().contains("$.Subject"));
    assertTrue(answer.rendered().contains("map-task-start-to-create-task"));
  }

  private static RuleFact ruleWriting(MappingQueryAnswer answer, String targetPath) {
    return answer.rules().stream()
        .filter(rule -> targetPath.equals(rule.targetPath()))
        .findFirst()
        .orElseThrow();
  }

  private static RequirementBrief mappedRockyBrief() {
    MappingTurnApplication application =
        MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start",
                    "create-task",
                    List.of(
                        new MappingIntentRule("name", "Subject", null),
                        new MappingIntentRule("", "Status", "Set to Not Started."))),
                new AddIntent(
                    "create-task",
                    "task-result",
                    List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")))));
    return application.brief();
  }

  private static RequirementBrief requestOnlyRockyBrief() {
    return MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start",
                    "create-task",
                    List.of(new MappingIntentRule("name", "Subject", null)))))
        .brief();
  }

  private static RequirementBrief requestMappedAndResponseSkippedBrief() {
    return MappingGapCoverage.skipUncovered(requestOnlyRockyBrief());
  }

  private static RequirementBrief briefWithUnresolvedStatus() {
    return rockyBrief()
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    "map-task-start-to-create-task",
                    "task-start",
                    MappingPort.OUTPUT,
                    "create-task",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "$.name", "$.Subject", null, MappingRuleStatus.USER_DEFINED),
                        new MappingIntentRule(
                            "", "$.Status", null, MappingRuleStatus.UNRESOLVED)))));
  }

  private static RequirementBrief rockyBrief() {
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Consume onTaskStart, create a Salesforce task, publish onTaskResult",
            "ref",
            "approved",
            List.of(),
            List.of())
        .withFlow(rockyFlow());
  }

  private static RequirementFlow rockyFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
            new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
            new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
        List.of(
            new Transition("task-start", "create-task"),
            new Transition("create-task", "task-result")));
  }
}
