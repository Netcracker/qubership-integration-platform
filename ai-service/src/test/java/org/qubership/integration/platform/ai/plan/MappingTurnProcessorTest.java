package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanism;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanismSelector;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation.TransitionRef;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanProjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class MappingTurnProcessorTest {

  static final String ENGLISH =
      "Map onTaskStart into createTask: name to Subject, Status is Not Started. Then map createTask"
          + " into onTaskResult: commandType is completeTask.";
  static final String VARIANT =
      "Put onTaskStart onto createTask with name as Subject and Status Not Started. Then put"
          + " createTask onto onTaskResult with commandType completeTask.";
  static final String PARAPHRASE =
      "Copy the task name onto Subject and set Status to Not Started when creating the Salesforce"
          + " task. On the way back, set commandType to completeTask.";
  static final String KEYWORD_PHRASE =
      "Request mapping from onTaskStart to createTask: sourcePath=name targetPath=Subject. Response"
          + " mapping: commandType completeTask.";
  static final String REQUEST_ONLY =
      "On the createTask request, copy name to Subject and set Status to Not Started.";
  static final String RESPONSE_ONLY =
      "On the onTaskResult payload, set commandType to completeTask.";
  static final String ORDINARY_CHAT = "Thanks, that helps.";
  static final String UNRELATED_QUESTION = "What time zone should the scheduler use?";
  static final String NEGATED_MAPPING = "Do not map any fields; forward the payload as-is.";
  static final String FIELD_NAMES_ONLY = "The payload has name, Subject, and Status fields.";

  @Test
  void englishVariantAndParaphraseConvergeOnTheSameNormalizedIntents() {
    MappingTurnAdapter adapter = fixtureAdapter();

    RequirementBrief english = process(rockyBrief(), ENGLISH, adapter).brief();
    RequirementBrief variant = process(rockyBrief(), VARIANT, adapter).brief();
    RequirementBrief paraphrase = process(rockyBrief(), PARAPHRASE, adapter).brief();

    assertEquals(normalizedState(english), normalizedState(variant));
    assertEquals(normalizedState(english), normalizedState(paraphrase));
    assertEquals(2, english.mappingIntents().size());
  }

  @Test
  void removingLanguageSpecificPhrasesDoesNotChangeMappingState() {
    MappingTurnAdapter adapter = fixtureAdapter();

    RequirementBrief withKeywords = process(rockyBrief(), KEYWORD_PHRASE, adapter).brief();
    RequirementBrief withoutKeywords = process(rockyBrief(), PARAPHRASE, adapter).brief();

    assertEquals(normalizedState(withKeywords), normalizedState(withoutKeywords));
  }

  @Test
  void oneMessageWithRequestAndResponseMappingsAddsTwoIntents() {
    MappingTurnApplication application = process(rockyBrief(), ENGLISH, fixtureAdapter());

    assertTrue(application.applied());
    assertEquals(2, application.brief().mappingIntents().size());
    MappingIntent request = intentAt(application.brief(), "task-start", "create-task");
    MappingIntent response = intentAt(application.brief(), "create-task", "task-result");
    assertEquals(2, request.rules().size());
    assertEquals(1, response.rules().size());
    assertTrue(MappingGapCoverage.uncovered(application.brief()).isEmpty());
  }

  @Test
  void sequentialMessagesExtendTheLatestStateInsteadOfReplacingIt() {
    MappingTurnAdapter adapter = fixtureAdapter();

    MappingTurnApplication first = process(rockyBrief(), REQUEST_ONLY, adapter);
    assertEquals(1, first.brief().mappingIntents().size());
    MappingTurnApplication second = process(first.brief(), RESPONSE_ONLY, adapter);

    assertEquals(2, second.brief().mappingIntents().size());
    assertEquals(
        intentAt(first.brief(), "task-start", "create-task").mappingIntentId(),
        intentAt(second.brief(), "task-start", "create-task").mappingIntentId());
    assertEquals(2, intentAt(second.brief(), "task-start", "create-task").rules().size());
  }

  @Test
  void onTaskStartCreateTaskOnTaskResultConvergesForOneAndTwoMessages() {
    MappingTurnAdapter adapter = fixtureAdapter();

    RequirementBrief oneMessage = process(rockyBrief(), ENGLISH, adapter).brief();
    RequirementBrief twoMessages =
        process(process(rockyBrief(), REQUEST_ONLY, adapter).brief(), RESPONSE_ONLY, adapter)
            .brief();

    assertEquals(normalizedState(oneMessage), normalizedState(twoMessages));
    assertEquals("map-task-start-to-create-task", intentAt(oneMessage, "task-start", "create-task").mappingIntentId());
    assertEquals(
        "map-create-task-to-task-result",
        intentAt(oneMessage, "create-task", "task-result").mappingIntentId());
  }

  @Test
  void oneMessageCanCoverSeveralBoundariesAndSeveralRulesOnOneIntent() {
    MappingTurnApplication application =
        process(rockyBrief(), "full mapping block", richFixtureAdapter());

    assertTrue(application.applied());
    MappingIntent request = intentAt(application.brief(), "task-start", "create-task");
    MappingIntent response = intentAt(application.brief(), "create-task", "task-result");
    assertEquals(6, request.rules().size());
    assertEquals(3, response.rules().size());
    assertTrue(hasRule(request, "Subject"));
    assertTrue(hasExpression(request, "Description", "{name} task"));
    assertTrue(hasExpression(request, "Priority", "high or urgent or critical maps to High"));
    assertTrue(hasRule(request, "Status"));
    assertTrue(hasExpression(request, "Comment", "use empty string when comment is missing"));
    assertTrue(hasExpression(request, "payload", "construct JSON object with name and priority"));
    assertTrue(hasRule(response, "executionId"));
    assertTrue(hasExpression(response, "commandType", "Set to completeTask."));
    assertTrue(hasExpression(response, "error", "map failure errors when success is false"));
  }

  @Test
  void ordinaryChatUnrelatedQuestionsNegatedMappingAndFieldNamesDoNotCreateIntents() {
    MappingTurnAdapter adapter = fixtureAdapter();

    assertTrue(process(rockyBrief(), ORDINARY_CHAT, adapter).brief().mappingIntents().isEmpty());
    assertTrue(
        process(rockyBrief(), UNRELATED_QUESTION, adapter).brief().mappingIntents().isEmpty());
    assertTrue(process(rockyBrief(), NEGATED_MAPPING, adapter).brief().mappingIntents().isEmpty());
    assertTrue(process(rockyBrief(), FIELD_NAMES_ONLY, adapter).brief().mappingIntents().isEmpty());
    assertFalse(process(rockyBrief(), ORDINARY_CHAT, adapter).applied());
  }

  @Test
  void typedTransitionCoverageUsesMappingIntentsNotDocumentPhrases() {
    RequirementBrief mapped = process(rockyBrief(), PARAPHRASE, fixtureAdapter()).brief();

    assertTrue(MappingGapCoverage.uncovered(mapped).isEmpty());
    assertEquals(2, MappingGapCoverage.uncovered(rockyBrief()).size());
  }

  @Test
  void capturedScriptPreferenceSelectsScriptForEnglishAndNonEnglishExpressions() {
    MappingTurnAdapter adapter =
        (brief, message) ->
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start",
                    "create-task",
                    List.of(rule("name", "Subject", expressionFor(message))),
                    "SCRIPT"));

    MappingIntent english =
        process(rockyBrief(), "uppercase the name with a script", adapter)
            .brief()
            .mappingIntents()
            .getFirst();
    MappingIntent variant =
        process(rockyBrief(), "mettre l'identifiant en majuscules avec un script", adapter)
            .brief()
            .mappingIntents()
            .getFirst();
    MappingIntent languageNeutral =
        process(rockyBrief(), "normalize the identifier using SCRIPT", adapter)
            .brief()
            .mappingIntents()
            .getFirst();

    assertEquals("SCRIPT", english.implementationPreference());
    assertEquals(MappingMechanism.SCRIPT, MappingMechanismSelector.select(english).orElse(null));
    assertEquals(MappingMechanismSelector.select(english), MappingMechanismSelector.select(variant));
    assertEquals(
        MappingMechanismSelector.select(english),
        MappingMechanismSelector.select(languageNeutral));
  }

  @Test
  void sequentialAddRuleExtendsTheSameIntent() {
    MappingTurnAdapter first =
        (brief, message) ->
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start", "create-task", List.of(rule("name", "Subject", null))));
    RequirementBrief afterFirst = process(rockyBrief(), REQUEST_ONLY, first).brief();
    String intentId = afterFirst.mappingIntents().getFirst().mappingIntentId();
    MappingTurnAdapter second =
        (brief, message) ->
            MappingTurnResult.changes(
                new AddRule(intentId, "", "Status", "Set to Not Started."));

    RequirementBrief afterSecond = process(afterFirst, "also set Status", second).brief();

    assertEquals(1, afterSecond.mappingIntents().size());
    assertEquals(intentId, afterSecond.mappingIntents().getFirst().mappingIntentId());
    assertEquals(2, afterSecond.mappingIntents().getFirst().rules().size());
  }

  @Test
  void telemetryRecordsOutcomeKindsCountsAndLatencyWithoutRawProse() {
    MappingTurnTelemetry telemetry = new MappingTurnTelemetry();

    MappingTurnProcessor.process(rockyBrief(), ENGLISH, fixtureAdapter(), telemetry);

    assertEquals(1, telemetry.events().size());
    MappingTurnTelemetry.Event event = telemetry.events().getFirst();
    assertEquals("CHANGES", event.outcomeType());
    assertEquals(List.of("ADD_INTENT"), event.operationKinds());
    assertEquals(2, event.affectedIntentCount());
    assertEquals(3, event.affectedRuleCount());
    assertEquals("APPLIED", event.validationResult());
    assertTrue(event.latencyMs() >= 0);
    assertEquals("", event.clarificationReason());
    String rendered = event.toString();
    assertFalse(rendered.contains(ENGLISH));
    assertFalse(rendered.contains("Not Started"));
    assertFalse(rendered.contains("completeTask"));
    assertFalse(rendered.contains("Subject"));
  }

  @Test
  void telemetryForNegativesAndClarificationDoesNotLogBusinessValues() {
    MappingTurnTelemetry telemetry = new MappingTurnTelemetry();
    MappingTurnProcessor.process(rockyBrief(), ORDINARY_CHAT, fixtureAdapter(), telemetry);
    MappingTurnProcessor.process(
        rockyBrief(),
        "which hop?",
        (brief, message) ->
            new MappingTurnResult.Clarification(
                "AMBIGUOUS_TRANSITION", List.of("create-task", "task-result")),
        telemetry);

    MappingTurnTelemetry.Event none = telemetry.events().getFirst();
    MappingTurnTelemetry.Event clarification = telemetry.events().get(1);
    assertEquals("NONE", none.outcomeType());
    assertEquals("NOT_APPLIED", none.validationResult());
    assertEquals("CLARIFICATION", clarification.outcomeType());
    assertEquals("AMBIGUOUS_TRANSITION", clarification.clarificationReason());
    assertEquals("NOT_APPLIED", clarification.validationResult());
    assertFalse(clarification.toString().contains("create-task"));
    assertFalse(none.toString().contains(ORDINARY_CHAT));
  }

  @Test
  void queryReadsFactsFromTheStoredBriefWithoutApplyingChanges() {
    RequirementBrief brief = process(rockyBrief(), REQUEST_ONLY, fixtureAdapter()).brief();
    MappingTurnAdapter adapter =
        (current, message) ->
            new Query(
                new MappingQuerySelector(
                    null,
                    "task-start",
                    "create-task",
                    null,
                    "Subject",
                    false,
                    MappingQuerySelector.Coverage.ANY));

    MappingTurnApplication application = process(brief, "What writes Subject?", adapter);

    assertFalse(application.applied());
    assertEquals(brief, application.brief());
    assertNotNull(application.answer());
    assertTrue(application.answer().matchFound());
    assertEquals("$.Subject", application.answer().rules().getFirst().targetPath());
    assertEquals("$.name", application.answer().rules().getFirst().sourcePath());
    assertEquals(
        "map-task-start-to-create-task",
        application.answer().rules().getFirst().mappingIntentId());
  }

  @Test
  void queryAnswerKeepsPathsVerbatimAcrossParaphrases() {
    RequirementBrief brief = process(rockyBrief(), REQUEST_ONLY, fixtureAdapter()).brief();
    MappingTurnAdapter adapter =
        (current, message) ->
            new Query(
                new MappingQuerySelector(
                    null, null, null, null, "Subject", false, MappingQuerySelector.Coverage.ANY));

    MappingTurnApplication english = process(brief, "What writes Subject?", adapter);
    MappingTurnApplication variant = process(brief, "Which field writes Subject?", adapter);

    assertEquals("en", english.answer().language());
    assertEquals("en", variant.answer().language());
    assertTrue(english.answer().rendered().contains("$.Subject"));
    assertTrue(variant.answer().rendered().contains("$.Subject"));
    assertTrue(variant.answer().rendered().contains(" writes "));
    assertEquals(brief, english.brief());
    assertEquals(brief, variant.brief());
  }

  @Test
  void queryDoesNotReopenTheBriefOrInvalidateAPlan() {
    RequirementBrief brief =
        MappingGapCoverage.skipUncovered(
            process(rockyBrief(), REQUEST_ONLY, fixtureAdapter()).brief());
    DesignExecutionPlan plan = planDependingOn("map-task-start-to-create-task");
    MappingTurnAdapter adapter =
        (current, message) ->
            new Query(
                new MappingQuerySelector(
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    MappingQuerySelector.Coverage.PASS_THROUGH));

    MappingTurnApplication application =
        process(brief, "Which transitions are pass-through?", adapter);

    assertUnchangedApprovalAndPlan(brief, application, plan);
    assertTrue(application.answer().matchFound());
    assertTrue(application.answer().transitions().getFirst().passThrough());
  }

  @Test
  void clarificationDoesNotReopenTheBriefOrInvalidateAPlan() {
    RequirementBrief brief = process(rockyBrief(), REQUEST_ONLY, fixtureAdapter()).brief();
    DesignExecutionPlan plan = planDependingOn("map-task-start-to-create-task");
    MappingTurnAdapter adapter =
        (current, message) ->
            new Clarification("AMBIGUOUS_TRANSITION", List.of("create-task", "task-result"));

    MappingTurnApplication application = process(brief, "Which hop writes Subject?", adapter);

    assertUnchangedApprovalAndPlan(brief, application, plan);
    assertNull(application.answer());
  }

  @Test
  void failedInterpretationDoesNotReopenTheBriefOrInvalidateAPlan() {
    RequirementBrief brief = process(rockyBrief(), REQUEST_ONLY, fixtureAdapter()).brief();
    DesignExecutionPlan plan = planDependingOn("map-task-start-to-create-task");
    MappingTurnAdapter adapter = (current, message) -> MappingTurnResult.changes();

    MappingTurnApplication application = process(brief, "???", adapter);

    assertUnchangedApprovalAndPlan(brief, application, plan);
    assertNull(application.answer());
  }

  @Test
  void authorChangeToApprovedMappingReopensTheBriefAndRebuildsEveryPlanStep() {
    RequirementBrief approved = process(rockyBrief(), REQUEST_ONLY, fixtureAdapter()).brief();
    String intentId = approved.mappingIntents().getFirst().mappingIntentId();
    DesignExecutionPlan plan = threeStepPlan(intentId);
    MappingTurnAdapter adapter =
        (current, message) ->
            MappingTurnResult.changes(new UpdateRule(intentId, "Subject", "title", null, null));

    MappingTurnApplication application = process(approved, "Subject comes from title", adapter);

    assertTrue(application.applied());
    assertEquals(
        "$.title",
        application.brief().mappingIntents().getFirst().rules().getFirst().sourcePath());
    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(approved, application.brief(), plan);
    assertTrue(impact.briefReopened());
    assertEquals(Set.of(intentId), impact.changedMappingIntentIds());
    assertEquals(
        List.of("step-trigger", "step-transform-" + intentId, "step-script"),
        impact.invalidatedPlanStepIds());
  }

  @Test
  void rejectedStaleResultDoesNotReopenTheBriefOrInvalidateAPlan() {
    RequirementBrief brief = process(rockyBrief(), REQUEST_ONLY, fixtureAdapter()).brief();
    DesignExecutionPlan plan = planDependingOn("map-task-start-to-create-task");
    MappingIntent intent = brief.mappingIntents().getFirst();
    String confirmation =
        new MappingGapPassThroughConfirmation(
                "stale-revision",
                List.of(new TransitionRef(intent.sourceRef(), intent.targetRef())))
            .toJson();

    MappingTurnApplication application =
        process(brief, confirmation, (current, message) -> MappingTurnResult.changes());

    assertUnchangedApprovalAndPlan(brief, application, plan);
    Clarification stale = assertInstanceOf(Clarification.class, application.result());
    assertEquals("STALE_REVISION", stale.reason());
  }

  private static void assertUnchangedApprovalAndPlan(
      RequirementBrief before, MappingTurnApplication application, DesignExecutionPlan plan) {
    assertFalse(application.applied());
    assertEquals(before, application.brief());
    assertEquals(before.mappingIntents(), application.brief().mappingIntents());
    assertEquals(before.approvedDraftText(), application.brief().approvedDraftText());
    assertEquals(before.approvedDraftReference(), application.brief().approvedDraftReference());
    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(before, application.brief(), plan);
    assertFalse(impact.briefReopened());
    assertTrue(impact.invalidatedPlanStepIds().isEmpty());
    assertEquals("flow-1", plan.semanticRevisionId());
  }

  private static DesignExecutionPlan planDependingOn(String mappingIntentId) {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "normalized-design-flow/flow-1",
        "design-input-hash",
        "2024.4",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY,
        List.of(
            new DesignExecutionPlan.Step(
                "step-transform-" + mappingIntentId,
                1,
                "Configure mapper for " + mappingIntentId,
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-transformation-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-hash",
        Map.of("cip-transformation-generator", "h2"),
        Map.of(),
        "catalog-hash",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY_HASH);
  }

  private static DesignExecutionPlan threeStepPlan(String mappingIntentId) {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "normalized-design-flow/flow-1",
        "design-input-hash",
        "2024.4",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY,
        List.of(
            new DesignExecutionPlan.Step(
                "step-trigger",
                1,
                "Generate HTTP trigger",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-trigger-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-transform-" + mappingIntentId,
                2,
                "Configure mapper for " + mappingIntentId,
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-transformation-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-script",
                3,
                "Generate mapping script",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-script-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-hash",
        Map.of(
            "cip-trigger-generator",
            "h1",
            "cip-transformation-generator",
            "h2",
            "cip-script-generator",
            "h3"),
        Map.of(),
        "catalog-hash",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY_HASH);
  }

  private static MappingTurnApplication process(
      RequirementBrief brief, String message, MappingTurnAdapter adapter) {
    return MappingTurnProcessor.process(brief, message, adapter);
  }

  private static MappingTurnAdapter fixtureAdapter() {
    return (brief, message) -> {
      if (ORDINARY_CHAT.equals(message)
          || UNRELATED_QUESTION.equals(message)
          || NEGATED_MAPPING.equals(message)
          || FIELD_NAMES_ONLY.equals(message)) {
        return MappingTurnResult.changes();
      }
      boolean hasRequest = intentAtOrNull(brief, "task-start", "create-task") == null;
      boolean hasResponse = intentAtOrNull(brief, "create-task", "task-result") == null;
      if (REQUEST_ONLY.equals(message)) {
        return hasRequest ? MappingTurnResult.changes(requestIntent()) : MappingTurnResult.changes();
      }
      if (RESPONSE_ONLY.equals(message)) {
        return hasResponse
            ? MappingTurnResult.changes(responseIntent())
            : MappingTurnResult.changes();
      }
      if (hasRequest && hasResponse) {
        return MappingTurnResult.changes(requestIntent(), responseIntent());
      }
      if (hasRequest) {
        return MappingTurnResult.changes(requestIntent());
      }
      if (hasResponse) {
        return MappingTurnResult.changes(responseIntent());
      }
      return MappingTurnResult.changes();
    };
  }

  private static MappingTurnAdapter richFixtureAdapter() {
    return (brief, message) ->
        MappingTurnResult.changes(
            new AddIntent(
                "task-start",
                "create-task",
                List.of(
                    rule("name", "Subject", null),
                    rule("name", "Description", "{name} task"),
                    rule("priority", "Priority", "high or urgent or critical maps to High"),
                    rule("\"Not Started\"", "Status", null),
                    rule("comment", "Comment", "use empty string when comment is missing"),
                    rule("", "payload", "construct JSON object with name and priority"))),
            new AddIntent(
                "create-task",
                "task-result",
                List.of(
                    rule("id", "executionId", null),
                    rule("", "commandType", "Set to completeTask."),
                    rule("errors", "error", "map failure errors when success is false"))));
  }

  private static AddIntent requestIntent() {
    return new AddIntent(
        "task-start",
        "create-task",
        List.of(rule("name", "Subject", null), rule("", "Status", "Set to Not Started.")));
  }

  private static AddIntent responseIntent() {
    return new AddIntent(
        "create-task",
        "task-result",
        List.of(rule("", "commandType", "Set to completeTask.")));
  }

  private static String expressionFor(String message) {
    if (message.contains("majuscules")) {
      return "mettre l'identifiant en majuscules";
    }
    if (message.contains("normalize")) {
      return "normalize the identifier";
    }
    return "uppercase the name";
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

  private static List<String> normalizedState(RequirementBrief brief) {
    return brief.mappingIntents().stream()
        .map(
            intent ->
                intent.sourceRef()
                    + "->"
                    + intent.targetRef()
                    + "@"
                    + intent.sourcePort()
                    + "/"
                    + intent.targetPort()
                    + ":"
                    + intent.rules().stream()
                        .filter(rule -> rule.status() != MappingRuleStatus.UNRESOLVED)
                        .map(
                            rule ->
                                MappingContract.canonicalPath(rule.sourcePath())
                                    + ">"
                                    + MappingContract.canonicalPath(rule.targetPath())
                                    + "="
                                    + String.valueOf(rule.expression())
                                    + "/"
                                    + rule.status())
                        .toList())
        .toList();
  }

  private static MappingIntent intentAt(
      RequirementBrief brief, String sourceRef, String targetRef) {
    MappingIntent intent = intentAtOrNull(brief, sourceRef, targetRef);
    if (intent == null) {
      throw new AssertionError("missing mapping " + sourceRef + " -> " + targetRef);
    }
    return intent;
  }

  private static MappingIntent intentAtOrNull(
      RequirementBrief brief, String sourceRef, String targetRef) {
    return brief.mappingIntents().stream()
        .filter(
            intent -> sourceRef.equals(intent.sourceRef()) && targetRef.equals(intent.targetRef()))
        .findFirst()
        .orElse(null);
  }

  private static boolean hasRule(MappingIntent intent, String targetPath) {
    String canonical = MappingContract.canonicalPath(targetPath);
    return intent.rules().stream()
        .anyMatch(rule -> canonical.equals(MappingContract.canonicalPath(rule.targetPath())));
  }

  private static boolean hasExpression(MappingIntent intent, String targetPath, String expression) {
    String canonical = MappingContract.canonicalPath(targetPath);
    return intent.rules().stream()
        .anyMatch(
            rule ->
                canonical.equals(MappingContract.canonicalPath(rule.targetPath()))
                    && expression.equals(rule.expression()));
  }
}
