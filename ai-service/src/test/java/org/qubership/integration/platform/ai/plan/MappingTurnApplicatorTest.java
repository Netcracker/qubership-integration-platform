package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.ConfirmationRequired;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class MappingTurnApplicatorTest {

  @Test
  void addIntentAssignsRuntimeIdAndPortsFromApprovedTransition() {
    RequirementBrief brief = rockyBrief();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new AddIntent(
                        "task-start",
                        "create-task",
                        List.of(rule("name", "Subject", null)))))
            .interpret(brief, "map name to Subject");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertTrue(application.applied());
    assertEquals(1, application.brief().mappingIntents().size());
    MappingIntent intent = application.brief().mappingIntents().getFirst();
    assertEquals("map-task-start-to-create-task", intent.mappingIntentId());
    assertEquals("task-start", intent.sourceRef());
    assertEquals(MappingPort.OUTPUT, intent.sourcePort());
    assertEquals("create-task", intent.targetRef());
    assertEquals(MappingPort.REQUEST, intent.targetPort());
    assertEquals(List.of(userRule("name", "Subject", null)), normalizedRules(intent));
    assertEquals(1, MappingGapCoverage.uncovered(application.brief()).size());
  }

  @Test
  void oneResultCanAddTwoIntentsAndSeveralRules() {
    RequirementBrief brief = rockyBrief();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new AddIntent(
                        "task-start",
                        "create-task",
                        List.of(
                            rule("name", "Subject", null),
                            rule("", "Status", "Set to Not Started."))),
                    new AddIntent(
                        "create-task",
                        "task-result",
                        List.of(rule("", "commandType", "Set to completeTask.")))))
            .interpret(brief, "request and response mappings");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertTrue(application.applied());
    assertEquals(2, application.brief().mappingIntents().size());
    MappingIntent request = intentAt(application.brief(), "task-start", "create-task");
    MappingIntent response = intentAt(application.brief(), "create-task", "task-result");
    assertEquals(MappingPort.OUTPUT, request.sourcePort());
    assertEquals(MappingPort.REQUEST, request.targetPort());
    assertEquals(MappingPort.RESPONSE, response.sourcePort());
    assertEquals(MappingPort.REQUEST, response.targetPort());
    assertEquals(2, request.rules().size());
    assertEquals(1, response.rules().size());
    assertTrue(MappingGapCoverage.uncovered(application.brief()).isEmpty());
  }

  @Test
  void addRuleExtendsExistingIntentWithoutChangingUnrelatedState() {
    RequirementBrief brief = briefWithRequestMapping();
    MappingIntent before = brief.mappingIntents().getFirst();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new AddRule(before.mappingIntentId(), "", "Status", "Set to Not Started.")))
            .interpret(brief, "also set Status");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertTrue(application.applied());
    MappingIntent after = application.brief().mappingIntents().getFirst();
    assertEquals(before.mappingIntentId(), after.mappingIntentId());
    assertEquals(before.sourceRef(), after.sourceRef());
    assertEquals(before.sourcePort(), after.sourcePort());
    assertEquals(before.targetRef(), after.targetRef());
    assertEquals(before.targetPort(), after.targetPort());
    assertEquals(
        List.of(userRule("name", "Subject", null), userRule("", "Status", "Set to Not Started.")),
        normalizedRules(after));
  }

  @Test
  void updateRuleReplacesSourceTargetAndExpression() {
    RequirementBrief brief = briefWithRequestMapping();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new UpdateRule(intentId, "Subject", "title", "Summary", "{title} task")))
            .interpret(brief, "correct the Subject rule");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertTrue(application.applied());
    assertEquals(
        List.of(userRule("title", "Summary", "{title} task")),
        normalizedRules(application.brief().mappingIntents().getFirst()));
  }

  @Test
  void deleteRuleRemovesOneTargetAndKeepsTheIntent() {
    RequirementBrief brief = briefWithTwoRequestRules();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(new DeleteRule(intentId, "Status")))
            .interpret(brief, "drop Status");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertTrue(application.applied());
    assertEquals(1, application.brief().mappingIntents().size());
    assertEquals(
        List.of(userRule("name", "Subject", null)),
        normalizedRules(application.brief().mappingIntents().getFirst()));
  }

  @Test
  void deleteLastRuleDoesNotConvertTheTransitionToPassThrough() {
    RequirementBrief brief = briefWithRequestMapping();
    List<MappingIntent> before = brief.mappingIntents();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new DeleteRule(before.getFirst().mappingIntentId(), "Subject")))
            .interpret(brief, "remove the only rule");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertFalse(application.applied());
    assertEquals(before, application.brief().mappingIntents());
    assertEquals(brief, application.brief());
  }

  @Test
  void deleteIntentRemovesTheMappingAndLeavesPassThroughAsAbsence() {
    RequirementBrief brief = briefWithRequestMapping();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new DeleteIntent(brief.mappingIntents().getFirst().mappingIntentId())))
            .interpret(brief, "delete the mapping");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertTrue(application.applied());
    assertTrue(application.brief().mappingIntents().isEmpty());
    assertEquals(2, MappingGapCoverage.uncovered(application.brief()).size());
    assertTrue(
        application.brief().mappingIntents().stream()
            .noneMatch(intent -> intent.rules().isEmpty()));
  }

  @Test
  void movingAMappingIsDeletePlusAddWithANewIdentifier() {
    RequirementBrief brief = briefWithRequestMapping();
    String previousId = brief.mappingIntents().getFirst().mappingIntentId();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new DeleteIntent(previousId),
                    new AddIntent(
                        "create-task",
                        "task-result",
                        List.of(rule("name", "Subject", null)))))
            .interpret(brief, "move the mapping to the response hop");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertTrue(application.applied());
    assertEquals(1, application.brief().mappingIntents().size());
    MappingIntent moved = application.brief().mappingIntents().getFirst();
    assertEquals("map-create-task-to-task-result", moved.mappingIntentId());
    assertNotEquals(previousId, moved.mappingIntentId());
    assertEquals("create-task", moved.sourceRef());
    assertEquals(MappingPort.RESPONSE, moved.sourcePort());
    assertEquals("task-result", moved.targetRef());
    assertEquals(MappingPort.REQUEST, moved.targetPort());
  }

  @Test
  void duplicateTargetPathInOneTurnLeavesThePreviousBriefUnchanged() {
    RequirementBrief brief = briefWithRequestMapping();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new AddRule(
                        brief.mappingIntents().getFirst().mappingIntentId(),
                        "title",
                        "Subject",
                        null)))
            .interpret(brief, "add another Subject writer");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertFalse(application.applied());
    assertEquals(brief, application.brief());
  }

  @Test
  void validAndInvalidChangesInOneTurnApplyNeither() {
    RequirementBrief brief = rockyBrief();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new AddIntent(
                        "task-start",
                        "create-task",
                        List.of(rule("name", "Subject", null))),
                    new AddIntent(
                        "task-start",
                        "task-result",
                        List.of(rule("name", "Subject", null)))))
            .interpret(brief, "map a real hop and a missing hop");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertFalse(application.applied());
    assertEquals(brief, application.brief());
    assertTrue(application.brief().mappingIntents().isEmpty());
  }

  @Test
  void unknownOccurrenceLeavesThePreviousBriefUnchanged() {
    RequirementBrief brief = rockyBrief();
    MappingTurnResult typed =
        new FixedMappingTurnAdapter(
                MappingTurnResult.changes(
                    new AddIntent(
                        "missing-source",
                        "create-task",
                        List.of(rule("name", "Subject", null)))))
            .interpret(brief, "map a missing occurrence");

    MappingTurnApplication application = MappingTurnApplicator.apply(brief, typed);

    assertFalse(application.applied());
    assertEquals(brief, application.brief());
  }

  @Test
  void queryClarificationAndConfirmationDoNotChangeTheBrief() {
    RequirementBrief brief = briefWithRequestMapping();
    MappingQuerySelector selector =
        new MappingQuerySelector(
            brief.mappingIntents().getFirst().mappingIntentId(),
            "task-start",
            "create-task",
            "name",
            "Subject",
            false,
            MappingQuerySelector.Coverage.MAPPED);

    assertEquals(brief, MappingTurnApplicator.apply(brief, new Query(selector)).brief());
    assertFalse(MappingTurnApplicator.apply(brief, new Query(selector)).applied());
    assertEquals(
        brief,
        MappingTurnApplicator.apply(
                brief, new Clarification("Which hop writes Subject?", List.of("task-start")))
            .brief());
    assertEquals(
        brief,
        MappingTurnApplicator.apply(
                brief,
                new ConfirmationRequired(
                    ConfirmationRequired.Kind.DELETE_INTENT,
                    brief.mappingIntents().getFirst().mappingIntentId(),
                    null))
            .brief());
  }

  @Test
  void resultTypesCoverChangesQueryClarificationAndConfirmationWithoutAReplacementBrief() {
    MappingTurnResult changes =
        MappingTurnResult.changes(
            new AddIntent("task-start", "create-task", List.of(rule("name", "Subject", null))));
    MappingTurnResult query = new Query(MappingQuerySelector.unresolvedTargets());
    MappingTurnResult clarification =
        new Clarification("Which hop writes Subject?", List.of("createTask"));
    MappingTurnResult confirmation =
        new ConfirmationRequired(ConfirmationRequired.Kind.DELETE_LAST_RULE, "map-1", "Subject");

    assertInstanceOf(MappingTurnResult.Changes.class, changes);
    assertInstanceOf(Query.class, query);
    assertInstanceOf(Clarification.class, clarification);
    assertInstanceOf(ConfirmationRequired.class, confirmation);
    assertEquals(1, ((MappingTurnResult.Changes) changes).operations().size());
  }

  @Test
  void emptyAddIntentDoesNotCreateAPassThroughRow() {
    RequirementBrief brief = rockyBrief();
    MappingTurnApplication application =
        MappingTurnApplicator.apply(
            brief,
            MappingTurnResult.changes(new AddIntent("task-start", "create-task", List.of())));

    assertFalse(application.applied());
    assertEquals(brief, application.brief());
    assertTrue(application.brief().mappingIntents().isEmpty());
  }

  @Test
  void emptyChangesLeaveTransitionsUncovered() {
    RequirementBrief brief = rockyBrief();
    MappingTurnApplication application =
        MappingTurnApplicator.apply(brief, MappingTurnResult.changes());

    assertTrue(application.brief().mappingIntents().isEmpty());
    assertEquals(2, MappingGapCoverage.uncovered(brief).size());
  }

  @Test
  void classifiedUserRulesStayUserDefinedAndExistingValidationSeesRequiredTargets() {
    RequirementBrief brief = rockyBrief();
    MappingContract source =
        MappingContract.of(new MappingContract.Field("$.name", "string", false));
    MappingContract target =
        MappingContract.of(
            new MappingContract.Field("$.Subject", "string", true),
            new MappingContract.Field("$.Status", "string", true));
    MappingTurnResult typed =
        MappingTurnResult.changes(
            new AddIntent("task-start", "create-task", List.of(rule("name", "Subject", null))));

    MappingTurnApplication application =
        MappingTurnApplicator.apply(brief, typed, source, target);

    assertTrue(application.applied());
    MappingIntent intent = application.brief().mappingIntents().getFirst();
    assertEquals(MappingRuleStatus.USER_DEFINED, intent.rules().getFirst().status());
    assertTrue(BriefMappingValidator.blocksApproval(application.brief()));
    assertTrue(
        BriefMappingValidator.unresolvedRequiredTargets(application.brief()).stream()
            .anyMatch(path -> path.equals(MappingContract.canonicalPath("Status"))));
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
            "draft",
            List.of(),
            List.of())
        .withFlow(rockyFlow());
  }

  private static RequirementBrief briefWithRequestMapping() {
    MappingTurnApplication application =
        MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start", "create-task", List.of(rule("name", "Subject", null)))));
    assertTrue(application.applied());
    return application.brief();
  }

  private static RequirementBrief briefWithTwoRequestRules() {
    MappingTurnApplication application =
        MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start",
                    "create-task",
                    List.of(
                        rule("name", "Subject", null),
                        rule("", "Status", "Set to Not Started.")))));
    assertTrue(application.applied());
    return application.brief();
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

  private static MappingIntentRule rule(String sourcePath, String targetPath, String expression) {
    return new MappingIntentRule(sourcePath, targetPath, expression);
  }

  private static MappingIntentRule userRule(
      String sourcePath, String targetPath, String expression) {
    return new MappingIntentRule(
        MappingContract.canonicalPath(sourcePath),
        MappingContract.canonicalPath(targetPath),
        expression,
        MappingRuleStatus.USER_DEFINED);
  }

  private static List<MappingIntentRule> normalizedRules(MappingIntent intent) {
    return intent.rules().stream()
        .filter(rule -> rule.status() != MappingRuleStatus.UNRESOLVED)
        .map(
            rule ->
                new MappingIntentRule(
                    MappingContract.canonicalPath(rule.sourcePath()),
                    MappingContract.canonicalPath(rule.targetPath()),
                    rule.expression(),
                    rule.status()))
        .toList();
  }

  private static MappingIntent intentAt(RequirementBrief brief, String sourceRef, String targetRef) {
    return brief.mappingIntents().stream()
        .filter(intent -> sourceRef.equals(intent.sourceRef()) && targetRef.equals(intent.targetRef()))
        .findFirst()
        .orElseThrow();
  }

  /** Supplies typed operations without a language model. */
  private static final class FixedMappingTurnAdapter {
    private final MappingTurnResult result;

    private FixedMappingTurnAdapter(MappingTurnResult result) {
      this.result = result;
    }

    MappingTurnResult interpret(RequirementBrief brief, String message) {
      assertNotNull(brief);
      assertNotNull(message);
      return result;
    }
  }
}
