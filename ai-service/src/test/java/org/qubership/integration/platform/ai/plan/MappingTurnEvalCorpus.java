package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.IntentChange;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.Kind;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.QuerySelector;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.RuleChange;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation.TransitionRef;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanProjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/** Conversation-seam cases for multilingual mapping-turn evaluation. */
final class MappingTurnEvalCorpus {

  enum Subset {
    FIXTURE,
    GOLD_CAPTURE,
    HARD_NEGATIVE,
    AMBIGUITY,
    GOLD_QUERY,
    REOPEN
  }

  enum Language {
    ENGLISH,
    PARAPHRASE
  }

  enum Expectation {
    STATE,
    EMPTY_INTENTS,
    SAFE_NO_WRITE,
    QUERY,
    REOPEN_CHANGE,
    REOPEN_STABLE
  }

  record Turn(String message, MappingTurnCapture capture) {}

  record ConversationCase(
      String id,
      Subset subset,
      Language language,
      RequirementBrief seed,
      List<Turn> turns,
      Expectation expectation,
      RequirementBrief goldBrief,
      MappingQuerySelector goldQuery,
      String clarificationReason,
      DesignExecutionPlan plan) {}

  private MappingTurnEvalCorpus() {}

  static List<ConversationCase> conversationCases() {
    List<ConversationCase> cases = new ArrayList<>();
    cases.addAll(fixtureCases());
    cases.addAll(goldCaptureCases());
    cases.addAll(hardNegativeCases());
    cases.addAll(ambiguityCases());
    cases.addAll(goldQueryCases());
    cases.addAll(reopenCases());
    return List.copyOf(cases);
  }

  static Map<String, MappingTurnCapture> captureIndex(List<ConversationCase> cases) {
    Map<String, MappingTurnCapture> index = new LinkedHashMap<>();
    for (ConversationCase conversationCase : cases) {
      for (Turn turn : conversationCase.turns()) {
        index.put(turn.message(), turn.capture());
      }
    }
    return Map.copyOf(index);
  }

  private static List<ConversationCase> fixtureCases() {
    RequirementBrief gold = mappedRocky();
    return List.of(
        oneMessage(
            "fixture-en-one",
            Subset.FIXTURE,
            Language.ENGLISH,
            rocky(),
            ENGLISH,
            fullCapture(),
            Expectation.STATE,
            gold),
        oneMessage(
            "fixture-paraphrase-one",
            Subset.FIXTURE,
            Language.PARAPHRASE,
            rocky(),
            PARAPHRASE,
            fullCapture(),
            Expectation.STATE,
            gold),
        twoMessage(
            "fixture-en-two",
            Language.ENGLISH,
            REQUEST_ONLY,
            requestCapture(),
            RESPONSE_ONLY,
            responseCapture(),
            gold),
        twoMessage(
            "fixture-paraphrase-two",
            Language.PARAPHRASE,
            REQUEST_ONLY_PARAPHRASE,
            requestCapture(),
            RESPONSE_ONLY_PARAPHRASE,
            responseCapture(),
            gold));
  }

  private static List<ConversationCase> goldCaptureCases() {
    RequirementBrief request = requestOnly();
    RequirementBrief twoRules = twoRequestRules();
    RequirementBrief goldFollowUp = apply(request, new AddRule("", "", "Status", "Set to Not Started."));
    RequirementBrief goldCorrected =
        apply(request, new UpdateRule("map-task-start-to-create-task", "Subject", "title", null, null));
    RequirementBrief goldDeleted =
        apply(twoRules, new DeleteRule("map-task-start-to-create-task", "Status"));
    RequirementBrief goldRich = apply(rocky(), richOps());
    RequirementBrief goldExpression =
        apply(
            rocky(),
            new AddIntent(
                "task-start",
                "create-task",
                List.of(rule("name", "Description", "{name} task"))));
    RequirementBrief goldFallback =
        apply(
            rocky(),
            new AddIntent(
                "task-start",
                "create-task",
                List.of(
                    rule("comment", "Comment", "use empty string when comment is missing"))));
    RequirementBrief goldJson =
        apply(
            rocky(),
            new AddIntent(
                "task-start",
                "create-task",
                List.of(rule("", "payload", "construct JSON object with name and priority"))));
    RequirementBrief goldOutcomes =
        apply(
            rocky(),
            new AddIntent(
                "create-task",
                "task-result",
                List.of(
                    rule("id", "executionId", null),
                    rule("errors", "error", "map failure errors when success is false"))));
    return List.of(
        oneMessage(
            "gold-en-full",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            rocky(),
            ENGLISH,
            fullCapture(),
            Expectation.STATE,
            mappedRocky()),
        oneMessage(
            "gold-en-implicit",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            rocky(),
            PARAPHRASE,
            fullCapture(),
            Expectation.STATE,
            mappedRocky()),
        oneMessage(
            "gold-en-follow-up",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            request,
            "Also set Status to Not Started on createTask.",
            addStatusCapture(),
            Expectation.STATE,
            goldFollowUp),
        oneMessage(
            "gold-en-correction",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            request,
            "Subject comes from title.",
            updateSubjectCapture(),
            Expectation.STATE,
            goldCorrected),
        oneMessage(
            "gold-en-delete-rule",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            twoRules,
            "Drop the Status assignment on createTask.",
            deleteStatusCapture(),
            Expectation.STATE,
            goldDeleted),
        oneMessage(
            "gold-en-list",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            rocky(),
            "Full mapping block: name to Subject, {name} task to Description, high maps to"
                + " Priority, Status is Not Started, empty comment fallback, JSON payload; then"
                + " id to executionId, commandType completeTask, map failure errors.",
            richCapture(),
            Expectation.STATE,
            goldRich),
        oneMessage(
            "gold-en-expression",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            rocky(),
            "On createTask write Description from the template {name} task.",
            expressionCapture(),
            Expectation.STATE,
            goldExpression),
        oneMessage(
            "gold-en-fallback",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            rocky(),
            "On createTask use an empty string when comment is missing.",
            fallbackCapture(),
            Expectation.STATE,
            goldFallback),
        oneMessage(
            "gold-en-json",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            rocky(),
            "On createTask construct a JSON object with name and priority as payload.",
            jsonCapture(),
            Expectation.STATE,
            goldJson),
        oneMessage(
            "gold-en-outcomes",
            Subset.GOLD_CAPTURE,
            Language.ENGLISH,
            rocky(),
            "On onTaskResult copy id to executionId and map failure errors when success is false.",
            outcomesCapture(),
            Expectation.STATE,
            goldOutcomes));
  }

  private static List<ConversationCase> hardNegativeCases() {
    MappingTurnCapture none = noneCapture();
    RequirementBrief seed = rocky();
    return List.of(
        negative("hard-en-chat", Language.ENGLISH, "Thanks, that helps.", none, seed),
        negative(
            "hard-en-unrelated",
            Language.ENGLISH,
            "What time zone should the scheduler use?",
            none,
            seed),
        negative(
            "hard-en-negated",
            Language.ENGLISH,
            "Do not map any fields; forward the payload as-is.",
            none,
            seed),
        negative(
            "hard-en-fields",
            Language.ENGLISH,
            "The payload has name, Subject, and Status fields.",
            none,
            seed));
  }

  private static List<ConversationCase> ambiguityCases() {
    RequirementBrief request = requestOnly();
    RequirementBrief both = mappedRocky();
    return List.of(
        oneMessage(
            "amb-conflict-subject",
            Subset.AMBIGUITY,
            Language.ENGLISH,
            request,
            "Add another Subject writer from title.",
            addConflictingSubjectCapture(),
            Expectation.SAFE_NO_WRITE,
            request,
            "TARGET_CONFLICT"),
        oneMessage(
            "amb-zero-match",
            Subset.AMBIGUITY,
            Language.ENGLISH,
            request,
            "Rename Missing to Summary.",
            updateMissingCapture(),
            Expectation.SAFE_NO_WRITE,
            request,
            "ZERO_MATCH"),
        oneMessage(
            "amb-omitted-transition",
            Subset.AMBIGUITY,
            Language.ENGLISH,
            both,
            "Who writes id?",
            omittedIdCapture(),
            Expectation.SAFE_NO_WRITE,
            both,
            "OMITTED_TRANSITION"),
        oneMessage(
            "amb-friendly-name",
            Subset.AMBIGUITY,
            Language.ENGLISH,
            request,
            "Map onTaskResult.",
            new MappingTurnCapture(
                Kind.CLARIFICATION,
                List.of(),
                List.of(),
                "AMBIGUOUS_TRANSITION",
                List.of("create-task", "task-result")),
            Expectation.SAFE_NO_WRITE,
            request,
            "AMBIGUOUS_TRANSITION"),
        oneMessage(
            "amb-invalid-transition",
            Subset.AMBIGUITY,
            Language.ENGLISH,
            rocky(),
            "Map onTaskStart straight onto onTaskResult: name to Subject.",
            new MappingTurnCapture(
                Kind.CHANGES,
                List.of(
                    new IntentChange(
                        "onTaskStart",
                        "onTaskResult",
                        List.of(rule("name", "Subject", null)),
                        null)),
                List.of(),
                "",
                List.of()),
            Expectation.SAFE_NO_WRITE,
            rocky(),
            null));
  }

  private static List<ConversationCase> goldQueryCases() {
    RequirementBrief mapped = mappedRocky();
    RequirementBrief request = requestOnly();
    RequirementBrief unresolved = unresolvedStatus();
    DesignExecutionPlan plan = planDependingOn("map-task-start-to-create-task");
    return List.of(
        query(
            "query-en-target",
            Language.ENGLISH,
            mapped,
            "What writes Subject?",
            new QuerySelector(null, null, null, null, "Subject", false, "ANY"),
            new MappingQuerySelector(
                null, null, null, null, "Subject", false, MappingQuerySelector.Coverage.ANY),
            plan),
        query(
            "query-en-source",
            Language.ENGLISH,
            mapped,
            "Where is name used?",
            new QuerySelector(null, null, null, "name", null, false, "ANY"),
            new MappingQuerySelector(
                null, null, null, "name", null, false, MappingQuerySelector.Coverage.ANY),
            plan),
        query(
            "query-en-transition",
            Language.ENGLISH,
            mapped,
            "What mapping exists from onTaskStart to createTask?",
            new QuerySelector(null, "onTaskStart", "createTask", null, null, false, "ANY"),
            new MappingQuerySelector(
                null,
                "task-start",
                "create-task",
                null,
                null,
                false,
                MappingQuerySelector.Coverage.ANY),
            plan),
        query(
            "query-en-id",
            Language.ENGLISH,
            mapped,
            "Show mapping map-create-task-to-task-result.",
            new QuerySelector(
                "map-create-task-to-task-result", null, null, null, null, false, "ANY"),
            new MappingQuerySelector(
                "map-create-task-to-task-result",
                null,
                null,
                null,
                null,
                false,
                MappingQuerySelector.Coverage.ANY),
            plan),
        query(
            "query-en-mapped",
            Language.ENGLISH,
            request,
            "Which transitions have mappings?",
            new QuerySelector(null, null, null, null, null, false, "MAPPED"),
            new MappingQuerySelector(
                null, null, null, null, null, false, MappingQuerySelector.Coverage.MAPPED),
            plan),
        query(
            "query-en-pass-through",
            Language.ENGLISH,
            request,
            "Which transitions are pass-through?",
            new QuerySelector(null, null, null, null, null, false, "PASS_THROUGH"),
            new MappingQuerySelector(
                null, null, null, null, null, false, MappingQuerySelector.Coverage.PASS_THROUGH),
            plan),
        query(
            "query-en-unresolved",
            Language.ENGLISH,
            unresolved,
            "Which required targets remain unresolved?",
            new QuerySelector(null, null, null, null, null, true, "ANY"),
            MappingQuerySelector.unresolvedTargets(),
            plan));
  }

  private static List<ConversationCase> reopenCases() {
    RequirementBrief approved = requestOnly();
    DesignExecutionPlan plan = threeStepPlan("map-task-start-to-create-task");
    RequirementBrief updated =
        apply(
            approved,
            new UpdateRule("map-task-start-to-create-task", "Subject", "title", null, null));
    String stale =
        new MappingGapPassThroughConfirmation(
                "stale-revision", List.of(new TransitionRef("task-start", "create-task")))
            .toJson();
    return List.of(
        new ConversationCase(
            "reopen-author-change",
            Subset.REOPEN,
            Language.ENGLISH,
            approved,
            List.of(new Turn("Subject comes from title on the approved mapping.", updateSubjectCapture())),
            Expectation.REOPEN_CHANGE,
            updated,
            null,
            null,
            plan),
        new ConversationCase(
            "reopen-query-stable",
            Subset.REOPEN,
            Language.ENGLISH,
            approved,
            List.of(
                new Turn(
                    "Which transitions are pass-through after approval?",
                    queryCapture(
                        new QuerySelector(
                            null, null, null, null, null, false, "PASS_THROUGH")))),
            Expectation.REOPEN_STABLE,
            approved,
            new MappingQuerySelector(
                null, null, null, null, null, false, MappingQuerySelector.Coverage.PASS_THROUGH),
            null,
            plan),
        new ConversationCase(
            "reopen-clarification-stable",
            Subset.REOPEN,
            Language.ENGLISH,
            approved,
            List.of(
                new Turn(
                    "Which hop writes Subject?",
                    new MappingTurnCapture(
                        Kind.CLARIFICATION,
                        List.of(),
                        List.of(),
                        "AMBIGUOUS_TRANSITION",
                        List.of("create-task", "task-result")))),
            Expectation.REOPEN_STABLE,
            approved,
            null,
            "AMBIGUOUS_TRANSITION",
            plan),
        new ConversationCase(
            "reopen-stale-stable",
            Subset.REOPEN,
            Language.ENGLISH,
            approved,
            List.of(new Turn(stale, noneCapture())),
            Expectation.REOPEN_STABLE,
            approved,
            null,
            "STALE_REVISION",
            plan));
  }

  static final String ENGLISH =
      "Map onTaskStart into createTask: name to Subject, Status is Not Started. Then map createTask"
          + " into onTaskResult: commandType is completeTask.";
  static final String PARAPHRASE =
      "Copy the task name onto Subject and set Status to Not Started when creating the Salesforce"
          + " task. On the way back, set commandType to completeTask.";
  static final String REQUEST_ONLY =
      "On the createTask request, copy name to Subject and set Status to Not Started.";
  static final String RESPONSE_ONLY =
      "On the onTaskResult payload, set commandType to completeTask.";
  static final String REQUEST_ONLY_PARAPHRASE =
      "When creating the Salesforce task, copy the name onto Subject and mark Status Not Started.";
  static final String RESPONSE_ONLY_PARAPHRASE =
      "On the way back to OM, set commandType to completeTask.";

  private static ConversationCase oneMessage(
      String id,
      Subset subset,
      Language language,
      RequirementBrief seed,
      String message,
      MappingTurnCapture capture,
      Expectation expectation,
      RequirementBrief goldBrief) {
    return oneMessage(id, subset, language, seed, message, capture, expectation, goldBrief, null);
  }

  private static ConversationCase oneMessage(
      String id,
      Subset subset,
      Language language,
      RequirementBrief seed,
      String message,
      MappingTurnCapture capture,
      Expectation expectation,
      RequirementBrief goldBrief,
      String clarificationReason) {
    return new ConversationCase(
        id,
        subset,
        language,
        seed,
        List.of(new Turn(message, capture)),
        expectation,
        goldBrief,
        null,
        clarificationReason,
        null);
  }

  private static ConversationCase twoMessage(
      String id,
      Language language,
      String firstMessage,
      MappingTurnCapture firstCapture,
      String secondMessage,
      MappingTurnCapture secondCapture,
      RequirementBrief goldBrief) {
    return new ConversationCase(
        id,
        Subset.FIXTURE,
        language,
        rocky(),
        List.of(new Turn(firstMessage, firstCapture), new Turn(secondMessage, secondCapture)),
        Expectation.STATE,
        goldBrief,
        null,
        null,
        null);
  }

  private static ConversationCase negative(
      String id,
      Language language,
      String message,
      MappingTurnCapture capture,
      RequirementBrief seed) {
    return new ConversationCase(
        id,
        Subset.HARD_NEGATIVE,
        language,
        seed,
        List.of(new Turn(message, capture)),
        Expectation.EMPTY_INTENTS,
        seed,
        null,
        null,
        null);
  }

  private static ConversationCase query(
      String id,
      Language language,
      RequirementBrief seed,
      String message,
      QuerySelector captureSelector,
      MappingQuerySelector goldQuery,
      DesignExecutionPlan plan) {
    return new ConversationCase(
        id,
        Subset.GOLD_QUERY,
        language,
        seed,
        List.of(new Turn(message, queryCapture(captureSelector))),
        Expectation.QUERY,
        seed,
        goldQuery,
        null,
        plan);
  }

  private static MappingTurnCapture fullCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "onTaskStart",
                "createTask",
                List.of(rule("name", "Subject", null), rule("", "Status", "Set to Not Started.")),
                null),
            new IntentChange(
                "createTask",
                "onTaskResult",
                List.of(rule("", "commandType", "Set to completeTask.")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnCapture requestCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "onTaskStart",
                "createTask",
                List.of(rule("name", "Subject", null), rule("", "Status", "Set to Not Started.")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnCapture responseCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "createTask",
                "onTaskResult",
                List.of(rule("", "commandType", "Set to completeTask.")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnCapture addStatusCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(),
        List.of(new RuleChange("map-task-start-to-create-task", "", "Status", "Set to Not Started.")),
        "",
        List.of());
  }

  private static MappingTurnCapture updateSubjectCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(),
        List.of(),
        "",
        List.of(),
        null,
        List.of(
            new RuleChange("map-task-start-to-create-task", "title", "Subject", null, null, null, null)),
        List.of(),
        List.of());
  }

  private static MappingTurnCapture deleteStatusCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(),
        List.of(),
        "",
        List.of(),
        null,
        List.of(),
        List.of(new RuleChange("map-task-start-to-create-task", "", "Status", null)),
        List.of());
  }

  private static MappingTurnCapture richCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "onTaskStart",
                "createTask",
                List.of(
                    rule("name", "Subject", null),
                    rule("name", "Description", "{name} task"),
                    rule("priority", "Priority", "high or urgent or critical maps to High"),
                    rule("\"Not Started\"", "Status", null),
                    rule("comment", "Comment", "use empty string when comment is missing"),
                    rule("", "payload", "construct JSON object with name and priority")),
                null),
            new IntentChange(
                "createTask",
                "onTaskResult",
                List.of(
                    rule("id", "executionId", null),
                    rule("", "commandType", "Set to completeTask."),
                    rule("errors", "error", "map failure errors when success is false")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnResult.Operation[] richOps() {
    return new MappingTurnResult.Operation[] {
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
              rule("errors", "error", "map failure errors when success is false")))
    };
  }

  private static MappingTurnCapture expressionCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "onTaskStart",
                "createTask",
                List.of(rule("name", "Description", "{name} task")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnCapture fallbackCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "onTaskStart",
                "createTask",
                List.of(rule("comment", "Comment", "use empty string when comment is missing")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnCapture jsonCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "onTaskStart",
                "createTask",
                List.of(rule("", "payload", "construct JSON object with name and priority")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnCapture outcomesCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(
            new IntentChange(
                "createTask",
                "onTaskResult",
                List.of(
                    rule("id", "executionId", null),
                    rule("errors", "error", "map failure errors when success is false")),
                null)),
        List.of(),
        "",
        List.of());
  }

  private static MappingTurnCapture addConflictingSubjectCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(),
        List.of(new RuleChange("map-task-start-to-create-task", "title", "Subject", null)),
        "",
        List.of());
  }

  private static MappingTurnCapture updateMissingCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(),
        List.of(),
        "",
        List.of(),
        null,
        List.of(
            new RuleChange(
                "map-task-start-to-create-task", "title", "Missing", null, "Summary", null, null)),
        List.of(),
        List.of());
  }

  private static MappingTurnCapture omittedIdCapture() {
    return new MappingTurnCapture(
        Kind.CHANGES,
        List.of(),
        List.of(),
        "",
        List.of(),
        null,
        List.of(new RuleChange("", "title", "id", null)),
        List.of(),
        List.of());
  }

  private static MappingTurnCapture noneCapture() {
    return new MappingTurnCapture(Kind.NONE, List.of(), List.of(), "", List.of());
  }

  private static MappingTurnCapture queryCapture(QuerySelector selector) {
    return new MappingTurnCapture(Kind.QUERY, List.of(), List.of(), "", List.of(), selector);
  }

  static RequirementBrief rocky() {
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

  static RequirementBrief mappedRocky() {
    return apply(rocky(), requestIntent(), responseIntent());
  }

  static RequirementBrief requestOnly() {
    return apply(
        rocky(),
        new AddIntent("task-start", "create-task", List.of(rule("name", "Subject", null))));
  }

  private static RequirementBrief twoRequestRules() {
    return apply(
        rocky(),
        new AddIntent(
            "task-start",
            "create-task",
            List.of(rule("name", "Subject", null), rule("", "Status", "Set to Not Started."))));
  }

  private static RequirementBrief unresolvedStatus() {
    return rocky()
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

  private static AddIntent requestIntent() {
    return new AddIntent(
        "task-start",
        "create-task",
        List.of(rule("name", "Subject", null), rule("", "Status", "Set to Not Started.")));
  }

  private static AddIntent responseIntent() {
    return new AddIntent(
        "create-task", "task-result", List.of(rule("", "commandType", "Set to completeTask.")));
  }

  private static RequirementBrief apply(
      RequirementBrief seed, MappingTurnResult.Operation... operations) {
    if (operations.length == 1 && operations[0] instanceof AddRule add && add.mappingIntentId().isBlank()) {
      String intentId = seed.mappingIntents().getFirst().mappingIntentId();
      return MappingTurnApplicator.apply(
              seed,
              MappingTurnResult.changes(
                  new AddRule(intentId, add.sourcePath(), add.targetPath(), add.expression())))
          .brief();
    }
    return MappingTurnApplicator.apply(seed, MappingTurnResult.changes(operations)).brief();
  }

  private static MappingIntentRule rule(String sourcePath, String targetPath, String expression) {
    return new MappingIntentRule(sourcePath, targetPath, expression);
  }

  static DesignExecutionPlan planDependingOn(String mappingIntentId) {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "normalized-design-flow/flow-1",
        "design-input-hash",
        "2024.4",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY,
        List.of(planStep("step-transform-" + mappingIntentId, 1, mappingIntentId)),
        "design-plan-report",
        "report-hash",
        Map.of("cip-transformation-generator", "h2"),
        Map.of(),
        "catalog-hash",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY_HASH);
  }

  static DesignExecutionPlan threeStepPlan(String mappingIntentId) {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "normalized-design-flow/flow-1",
        "design-input-hash",
        "2024.4",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY,
        List.of(
            planStep("step-trigger", 1, "trigger"),
            planStep("step-transform-" + mappingIntentId, 2, mappingIntentId),
            planStep("step-script", 3, "script")),
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

  private static DesignExecutionPlan.Step planStep(String stepId, int order, String label) {
    return new DesignExecutionPlan.Step(
        stepId,
        order,
        "Configure " + label,
        DesignExecutionPlan.OwnerKind.SKILL,
        List.of("cip-script-generator"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of("GRAPH_PATCH_ARTIFACT"));
  }
}
