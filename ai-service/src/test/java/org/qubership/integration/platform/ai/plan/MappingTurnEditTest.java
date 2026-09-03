package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.ConfirmationRequired;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation.TransitionRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class MappingTurnEditTest {

  @Test
  void addRuleLeavesUnrelatedIntentsAndRulesUnchanged() {
    RequirementBrief brief = briefWithRequestAndResponse();
    MappingIntent responseBefore = intentAt(brief, "create-task", "task-result");
    String requestId = intentAt(brief, "task-start", "create-task").mappingIntentId();

    MappingTurnApplication application =
        process(
            brief,
            "also set Status",
            (current, message) ->
                MappingTurnResult.changes(
                    new AddRule(requestId, "", "Status", "Set to Not Started.")));

    assertTrue(application.applied());
    MappingIntent request = intentAt(application.brief(), "task-start", "create-task");
    MappingIntent response = intentAt(application.brief(), "create-task", "task-result");
    assertEquals(2, request.rules().stream().filter(this::active).count());
    assertTrue(hasTarget(request, "Status"));
    assertEquals(responseBefore, response);
    assertTrue(hasTarget(request, "Subject"));
  }

  @Test
  void updateTurnsCoverSourceTargetExpressionConstantAndFallbackIndependently() {
    RequirementBrief brief = briefWithTwoRequestRules();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();

    RequirementBrief afterSource =
        process(
                brief,
                "source is title",
                (current, message) ->
                    MappingTurnResult.changes(
                        new UpdateRule(intentId, "Subject", "title", null, null)))
            .brief();
    assertEquals(MappingContract.canonicalPath("title"), ruleAt(afterSource, "Subject").sourcePath());
    assertEquals(null, ruleAt(afterSource, "Subject").expression());

    RequirementBrief afterTarget =
        process(
                afterSource,
                "write Summary instead",
                (current, message) ->
                    MappingTurnResult.changes(
                        new UpdateRule(intentId, "Subject", "title", "Summary", null)))
            .brief();
    assertTrue(hasTarget(afterTarget.mappingIntents().getFirst(), "Summary"));
    assertFalse(hasTarget(afterTarget.mappingIntents().getFirst(), "Subject"));

    RequirementBrief afterExpression =
        process(
                afterTarget,
                "template the summary",
                (current, message) ->
                    MappingTurnResult.changes(
                        new UpdateRule(intentId, "Summary", "title", null, "{title} task")))
            .brief();
    assertEquals("{title} task", ruleAt(afterExpression, "Summary").expression());

    RequirementBrief afterConstant =
        process(
                afterExpression,
                "Status is Done",
                (current, message) ->
                    MappingTurnResult.changes(
                        new UpdateRule(intentId, "Status", "\"Done\"", null, null)))
            .brief();
    assertEquals("\"Done\"", ruleAt(afterConstant, "Status").sourcePath());
    assertEquals(null, ruleAt(afterConstant, "Status").expression());

    RequirementBrief afterFallback =
        process(
                afterConstant,
                "empty Status when missing",
                (current, message) ->
                    MappingTurnResult.changes(
                        new UpdateRule(
                            intentId,
                            "Status",
                            "status",
                            null,
                            "use empty string when status is missing")))
            .brief();
    assertEquals(
        "use empty string when status is missing", ruleAt(afterFallback, "Status").expression());
    assertEquals(MappingRuleStatus.USER_DEFINED, ruleAt(afterFallback, "Status").status());
    assertEquals(MappingRuleStatus.USER_DEFINED, ruleAt(afterFallback, "Summary").status());
  }

  @Test
  void addToAnExistingTargetIsAConflictNotLastWriteWins() {
    RequirementBrief brief = briefWithRequestMapping();
    List<MappingIntent> before = brief.mappingIntents();
    String intentId = before.getFirst().mappingIntentId();

    MappingTurnApplication application =
        process(
            brief,
            "add another Subject writer",
            (current, message) ->
                MappingTurnResult.changes(new AddRule(intentId, "title", "Subject", null)));

    assertFalse(application.applied());
    assertEquals(before, application.brief().mappingIntents());
    Clarification clarification = assertInstanceOf(Clarification.class, application.result());
    assertEquals("TARGET_CONFLICT", clarification.reason());
    assertTrue(clarification.candidates().stream().anyMatch(s -> s.contains(intentId)));
    assertTrue(clarification.candidates().stream().anyMatch(s -> s.contains("Subject")));
  }

  @Test
  void updateOrDeleteWithZeroOrSeveralMatchesAsksForClarification() {
    RequirementBrief brief = briefWithRequestAndResponse();
    String requestId = intentAt(brief, "task-start", "create-task").mappingIntentId();

    MappingTurnApplication zero =
        process(
            brief,
            "rename Missing",
            (current, message) ->
                MappingTurnResult.changes(
                    new UpdateRule(requestId, "Missing", "title", "Summary", null)));
    assertFalse(zero.applied());
    assertEquals(brief, zero.brief());
    Clarification zeroMatch = assertInstanceOf(Clarification.class, zero.result());
    assertEquals("ZERO_MATCH", zeroMatch.reason());
    assertTrue(zeroMatch.candidates().stream().anyMatch(s -> s.contains(requestId)));

    MappingTurnApplication omitted =
        process(
            brief,
            "who writes id",
            (current, message) ->
                new Clarification(
                    "OMITTED_TRANSITION",
                    List.of(
                        intentAt(brief, "task-start", "create-task").mappingIntentId() + ":id",
                        intentAt(brief, "create-task", "task-result").mappingIntentId()
                            + ":executionId")));
    assertFalse(omitted.applied());
    assertEquals(brief, omitted.brief());
    Clarification omittedTransition = assertInstanceOf(Clarification.class, omitted.result());
    assertEquals("OMITTED_TRANSITION", omittedTransition.reason());
    assertTrue(omittedTransition.candidates().size() >= 2);
  }

  @Test
  void deletingTheLastRuleRequiresTypedConfirmationNotAYesPhrase() {
    RequirementBrief brief = briefWithRequestMapping();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();

    MappingTurnApplication asked =
        process(
            brief,
            "remove the only rule",
            (current, message) -> MappingTurnResult.changes(new DeleteRule(intentId, "Subject")));

    assertFalse(asked.applied());
    assertEquals(brief, asked.brief());
    ConfirmationRequired required = assertInstanceOf(ConfirmationRequired.class, asked.result());
    assertEquals(ConfirmationRequired.Kind.DELETE_LAST_RULE, required.kind());
    assertEquals(intentId, required.mappingIntentId());

    MappingTurnApplication spokenYes =
        process(brief, "yes", (current, message) -> MappingTurnResult.changes());
    assertFalse(spokenYes.applied());
    assertEquals(brief.mappingIntents(), spokenYes.brief().mappingIntents());
  }

  @Test
  void typedPassThroughConfirmationDeletesTheLastRuleIntent() {
    RequirementBrief brief = briefWithRequestMapping();
    MappingIntent intent = brief.mappingIntents().getFirst();
    String confirmation =
        new MappingGapPassThroughConfirmation(
                MappingTurnProcessor.revisionOf(brief),
                List.of(new TransitionRef(intent.sourceRef(), intent.targetRef())))
            .toJson();

    MappingTurnApplication application =
        process(brief, confirmation, (current, message) -> MappingTurnResult.changes());

    assertTrue(application.applied());
    assertTrue(application.brief().mappingIntents().isEmpty());
  }

  @Test
  void deletingAnEntireIntentRequiresTypedConfirmation() {
    RequirementBrief brief = briefWithRequestMapping();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();

    MappingTurnApplication asked =
        process(
            brief,
            "delete the mapping",
            (current, message) -> MappingTurnResult.changes(new DeleteIntent(intentId)));
    assertFalse(asked.applied());
    assertEquals(brief, asked.brief());

    String confirmation =
        new MappingGapPassThroughConfirmation(
                MappingTurnProcessor.revisionOf(brief),
                List.of(new TransitionRef("task-start", "create-task")))
            .toJson();
    MappingTurnApplication confirmed =
        process(brief, confirmation, (current, message) -> MappingTurnResult.changes());
    assertTrue(confirmed.applied());
    assertTrue(confirmed.brief().mappingIntents().isEmpty());
  }

  @Test
  void userEditsAreUserDefinedAndIdentityAutoRulesStayAuto() {
    RequirementBrief brief = briefWithAutoAndUserRules();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();

    MappingTurnApplication application =
        process(
            brief,
            "Subject comes from title",
            (current, message) ->
                MappingTurnResult.changes(
                    new UpdateRule(intentId, "Subject", "title", null, null)));

    assertTrue(application.applied());
    MappingIntent intent = application.brief().mappingIntents().getFirst();
    assertEquals(MappingRuleStatus.USER_DEFINED, ruleAt(application.brief(), "Subject").status());
    assertEquals(MappingRuleStatus.AUTO, ruleAt(application.brief(), "name").status());
    assertTrue(
        intent.rules().stream().anyMatch(rule -> rule.status() == MappingRuleStatus.UNRESOLVED));
  }

  @Test
  void aFlowChangingRequestDoesNotEditMappingsAndDoesNotChangeTheFlow() {
    RequirementBrief brief = briefWithRequestMapping();
    RequirementFlow flow = brief.flow();

    MappingTurnApplication application =
        process(
            brief,
            "also consume a new Kafka topic",
            (current, message) ->
                new Clarification("FLOW_CHANGE", List.of("task-start", "create-task")));

    assertFalse(application.applied());
    assertEquals(brief, application.brief());
    assertEquals(flow, application.brief().flow());
    Clarification clarification = assertInstanceOf(Clarification.class, application.result());
    assertEquals("FLOW_CHANGE", clarification.reason());
  }

  @Test
  void severalChangesApplyTogetherAndOneConflictLeavesTheBriefUnchanged() {
    RequirementBrief brief = briefWithTwoRequestRules();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();

    MappingTurnApplication ok =
        process(
            brief,
            "drop Status and add Priority",
            (current, message) ->
                MappingTurnResult.changes(
                    new DeleteRule(intentId, "Status"),
                    new AddRule(intentId, "priority", "Priority", "high maps to High")));
    assertTrue(ok.applied());
    assertFalse(hasTarget(ok.brief().mappingIntents().getFirst(), "Status"));
    assertTrue(hasTarget(ok.brief().mappingIntents().getFirst(), "Priority"));
    assertTrue(hasTarget(ok.brief().mappingIntents().getFirst(), "Subject"));

    MappingTurnApplication conflicted =
        process(
            brief,
            "drop Status and add another Subject",
            (current, message) ->
                MappingTurnResult.changes(
                    new DeleteRule(intentId, "Status"),
                    new AddRule(intentId, "title", "Subject", null)));
    assertFalse(conflicted.applied());
    assertEquals(brief, conflicted.brief());
    assertTrue(hasTarget(conflicted.brief().mappingIntents().getFirst(), "Status"));
  }

  @Test
  void reprocessingTheSameAcceptedTurnIsIdempotent() {
    RequirementBrief brief = briefWithRequestMapping();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();
    MappingTurnAdapter addStatus =
        (current, message) ->
            MappingTurnResult.changes(new AddRule(intentId, "", "Status", "Set to Not Started."));

    MappingTurnApplication first = process(brief, "also set Status", addStatus);
    MappingTurnApplication replay = process(first.brief(), "also set Status", addStatus);

    assertTrue(first.applied());
    assertEquals(2, first.brief().mappingIntents().getFirst().rules().size());
    assertEquals(
        normalized(first.brief()),
        normalized(replay.brief()));
    assertEquals(2, replay.brief().mappingIntents().getFirst().rules().size());
  }

  @Test
  void aStaleInterpretedResultIsRejectedAndReinterpretedAgainstTheLatestBrief() {
    RequirementBrief original = briefWithRequestMapping();
    String intentId = original.mappingIntents().getFirst().mappingIntentId();
    String originalRevision = MappingTurnProcessor.revisionOf(original);
    MappingTurnResult staleAddStatus =
        MappingTurnResult.changes(new AddRule(intentId, "", "Status", "Set to Not Started."));

    RequirementBrief latest =
        process(
                original,
                "add Priority",
                (current, message) ->
                    MappingTurnResult.changes(
                        new AddRule(intentId, "priority", "Priority", null)))
            .brief();
    assertNotEquals(originalRevision, MappingTurnProcessor.revisionOf(latest));

    MappingTurnApplication stale =
        MappingTurnProcessor.applyResult(
            latest,
            staleAddStatus,
            originalRevision,
            "also set Status",
            (current, message) ->
                MappingTurnResult.changes(
                    new AddRule(intentId, "", "Status", "Set to Not Started.")));

    assertTrue(stale.applied());
    MappingIntent intent = stale.brief().mappingIntents().getFirst();
    assertTrue(hasTarget(intent, "Priority"));
    assertTrue(hasTarget(intent, "Status"));
    assertTrue(hasTarget(intent, "Subject"));
  }

  @Test
  void staleTypedConfirmationDoesNotDelete() {
    RequirementBrief brief = briefWithRequestMapping();
    MappingIntent intent = brief.mappingIntents().getFirst();
    String confirmation =
        new MappingGapPassThroughConfirmation(
                "stale-revision",
                List.of(new TransitionRef(intent.sourceRef(), intent.targetRef())))
            .toJson();

    MappingTurnApplication application =
        process(brief, confirmation, (current, message) -> MappingTurnResult.changes());

    assertFalse(application.applied());
    assertEquals(brief.mappingIntents(), application.brief().mappingIntents());
  }

  @Test
  void ambiguousFriendlyNamesReturnCandidates() {
    RequirementBrief brief = briefWithRequestMapping();
    MappingTurnApplication application =
        process(
            brief,
            "map onTaskResult",
            (current, message) ->
                new Clarification("AMBIGUOUS_TRANSITION", List.of("create-task", "task-result")));

    assertFalse(application.applied());
    assertEquals(brief, application.brief());
    Clarification clarification = assertInstanceOf(Clarification.class, application.result());
    assertEquals("AMBIGUOUS_TRANSITION", clarification.reason());
    assertTrue(clarification.candidates().contains("create-task"));
    assertTrue(clarification.candidates().contains("task-result"));
  }

  @Test
  void deleteRuleRemovesOneTargetThroughTheApplicator() {
    RequirementBrief brief = briefWithTwoRequestRules();
    String intentId = brief.mappingIntents().getFirst().mappingIntentId();

    MappingTurnApplication application =
        process(
            brief,
            "drop Status",
            (current, message) -> MappingTurnResult.changes(new DeleteRule(intentId, "Status")));

    assertTrue(application.applied());
    assertEquals(1, application.brief().mappingIntents().size());
    assertEquals(
        List.of(MappingContract.canonicalPath("Subject")),
        application.brief().mappingIntents().getFirst().rules().stream()
            .filter(this::active)
            .map(rule -> MappingContract.canonicalPath(rule.targetPath()))
            .toList());
  }

  private static MappingTurnApplication process(
      RequirementBrief brief, String message, MappingTurnAdapter adapter) {
    return MappingTurnProcessor.process(brief, message, adapter);
  }

  private static RequirementBrief briefWithRequestMapping() {
    return MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start", "create-task", List.of(rule("name", "Subject", null)))))
        .brief();
  }

  private static RequirementBrief briefWithTwoRequestRules() {
    return MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start",
                    "create-task",
                    List.of(
                        rule("name", "Subject", null),
                        rule("", "Status", "Set to Not Started.")))))
        .brief();
  }

  private static RequirementBrief briefWithRequestAndResponse() {
    return MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start", "create-task", List.of(rule("name", "Subject", null))),
                new AddIntent(
                    "create-task",
                    "task-result",
                    List.of(rule("id", "executionId", null)))))
        .brief();
  }

  private static RequirementBrief briefWithAutoAndUserRules() {
    RequirementBrief seeded =
        MappingTurnApplicator.apply(
                rockyBrief(),
                MappingTurnResult.changes(
                    new AddIntent(
                        "task-start",
                        "create-task",
                        List.of(rule("name", "Subject", null), rule("name", "name", null)))))
            .brief();
    MappingIntent intent = seeded.mappingIntents().getFirst();
    List<MappingIntentRule> rules =
        List.of(
            new MappingIntentRule("name", "Subject", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule("name", "name", null, MappingRuleStatus.AUTO),
            new MappingIntentRule("", "Status", null, MappingRuleStatus.UNRESOLVED));
    return seeded.withMappingIntents(List.of(intent.withRules(rules)));
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

  private static MappingIntent intentAt(
      RequirementBrief brief, String sourceRef, String targetRef) {
    return brief.mappingIntents().stream()
        .filter(
            intent -> sourceRef.equals(intent.sourceRef()) && targetRef.equals(intent.targetRef()))
        .findFirst()
        .orElseThrow();
  }

  private static MappingIntentRule ruleAt(RequirementBrief brief, String targetPath) {
    String canonical = MappingContract.canonicalPath(targetPath);
    return brief.mappingIntents().getFirst().rules().stream()
        .filter(rule -> canonical.equals(MappingContract.canonicalPath(rule.targetPath())))
        .findFirst()
        .orElseThrow();
  }

  private boolean active(MappingIntentRule rule) {
    return rule.status() != MappingRuleStatus.UNRESOLVED;
  }

  private static boolean hasTarget(MappingIntent intent, String targetPath) {
    String canonical = MappingContract.canonicalPath(targetPath);
    return intent.rules().stream()
        .anyMatch(rule -> canonical.equals(MappingContract.canonicalPath(rule.targetPath())));
  }

  private static List<String> normalized(RequirementBrief brief) {
    return brief.mappingIntents().stream()
        .map(
            intent ->
                intent.mappingIntentId()
                    + ":"
                    + intent.rules().stream()
                        .map(
                            rule ->
                                MappingContract.canonicalPath(rule.sourcePath())
                                    + ">"
                                    + MappingContract.canonicalPath(rule.targetPath())
                                    + "="
                                    + rule.expression()
                                    + "/"
                                    + rule.status())
                        .toList())
        .toList();
  }
}
