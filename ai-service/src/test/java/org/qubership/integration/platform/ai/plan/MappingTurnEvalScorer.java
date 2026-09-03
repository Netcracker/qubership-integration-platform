package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalCorpus.ConversationCase;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalCorpus.Language;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalCorpus.Subset;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingQueryAnswer.RuleFact;
import org.qubership.integration.platform.ai.plan.MappingQueryAnswer.TransitionFact;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Scores conversation-seam outcomes against gold mapping state and user-visible results. */
public final class MappingTurnEvalScorer {

  record CaseScore(
      String id,
      Subset subset,
      Language language,
      boolean exactState,
      boolean intentMatch,
      Set<String> goldIntents,
      Set<String> actualIntents,
      Set<String> goldRules,
      Set<String> actualRules,
      boolean clarification,
      boolean createdIntent,
      boolean unsafeEdit,
      boolean factualMatch,
      boolean briefChanged,
      boolean approvalChanged,
      boolean planChanged,
      boolean reopenPassed,
      String detail) {}

  record SubsetMetrics(
      Subset subset,
      Language language,
      int cases,
      double exactState,
      double intentPrecision,
      double intentRecall,
      double rulePrecision,
      double ruleRecall,
      double clarificationRate,
      double mappingIntentCreationRate,
      double unsafeEditRate,
      double exactFactualMatch,
      double briefChangeRate,
      double approvalChangeRate,
      double planChangeRate,
      double passRate) {}

  public record RepairScore(String id, boolean passed, int agentCalls, boolean secondLoop, String detail) {}

  record Report(
      List<CaseScore> cases,
      List<SubsetMetrics> metrics,
      List<RepairScore> repairs,
      double compilerRepairPassRate,
      String liveModelSlice,
      List<String> failures) {}

  private MappingTurnEvalScorer() {}

  static CaseScore score(ConversationCase conversationCase, MappingTurnApplication application) {
    RequirementBrief actual = application.brief();
    RequirementBrief gold = conversationCase.goldBrief();
    Set<String> goldIntents = intentKeys(gold);
    Set<String> actualIntents = intentKeys(actual);
    Set<String> goldRules = ruleKeys(gold);
    Set<String> actualRules = ruleKeys(actual);
    boolean exactState = normalizedState(gold).equals(normalizedState(actual));
    boolean clarification = application.result() instanceof Clarification;
    boolean createdIntent = !conversationCase.seed().mappingIntents().equals(actual.mappingIntents())
        && actual.mappingIntents().size() > conversationCase.seed().mappingIntents().size();
    boolean briefChanged = !conversationCase.seed().equals(actual);
    boolean approvalChanged =
        !Objects.equals(
                conversationCase.seed().approvedDraftText(), actual.approvedDraftText())
            || !Objects.equals(
                conversationCase.seed().approvedDraftReference(),
                actual.approvedDraftReference());
    boolean planChanged = false;
    boolean unsafeEdit = false;
    boolean factualMatch = false;
    boolean reopenPassed = false;
    String detail = "";
    switch (conversationCase.expectation()) {
      case STATE -> {
        unsafeEdit = !exactState && application.applied();
        detail = exactState ? "state match" : "state mismatch " + normalizedState(actual);
      }
      case EMPTY_INTENTS -> {
        createdIntent = !actual.mappingIntents().isEmpty();
        detail = createdIntent ? "created mapping intents" : "no mapping intents";
      }
      case SAFE_NO_WRITE -> {
        boolean unchanged = conversationCase.seed().equals(actual);
        boolean safeOutcome = !application.applied() && unchanged;
        unsafeEdit = !safeOutcome;
        if (conversationCase.clarificationReason() != null
            && application.result() instanceof Clarification(var reason, var ignored)) {
          safeOutcome = safeOutcome && conversationCase.clarificationReason().equals(reason);
          unsafeEdit = !safeOutcome;
        }
        detail = unsafeEdit ? "unsafe write" : "safe no-write";
      }
      case QUERY -> {
        MappingQueryAnswer expected =
            MappingQueryLookup.answer(conversationCase.seed(), conversationCase.goldQuery());
        factualMatch = sameFacts(expected, application.answer());
        planChanged = planImpact(conversationCase, application);
        unsafeEdit = application.applied();
        detail = factualMatch ? "factual match" : "factual mismatch";
      }
      case REOPEN_CHANGE -> {
        BriefMappingReview.MappingChangeImpact impact =
            BriefMappingReview.afterApprovedMappingChange(
                conversationCase.seed(), actual, conversationCase.plan());
        boolean stepsInvalidated =
            conversationCase.plan() != null
                && impact.invalidatedPlanStepIds().equals(allStepIds(conversationCase.plan()));
        reopenPassed = application.applied() && impact.briefReopened() && stepsInvalidated && exactState;
        planChanged = impact.briefReopened();
        detail = reopenPassed ? "reopened and rebuilt" : "reopen failed";
      }
      case REOPEN_STABLE -> {
        BriefMappingReview.MappingChangeImpact impact =
            BriefMappingReview.afterApprovedMappingChange(
                conversationCase.seed(), actual, conversationCase.plan());
        planChanged = impact.briefReopened() || !impact.invalidatedPlanStepIds().isEmpty();
        reopenPassed =
            !application.applied()
                && !impact.briefReopened()
                && impact.invalidatedPlanStepIds().isEmpty()
                && !briefChanged
                && !approvalChanged;
        detail = reopenPassed ? "approval unchanged" : "approval changed";
      }
    }
    return new CaseScore(
        conversationCase.id(),
        conversationCase.subset(),
        conversationCase.language(),
        exactState,
        goldIntents.equals(actualIntents),
        goldIntents,
        actualIntents,
        goldRules,
        actualRules,
        clarification,
        createdIntent,
        unsafeEdit,
        factualMatch,
        briefChanged,
        approvalChanged,
        planChanged,
        reopenPassed,
        detail);
  }

  static Report report(List<CaseScore> cases, List<RepairScore> repairs, String liveModelSlice) {
    List<SubsetMetrics> metrics = new ArrayList<>();
    metrics.addAll(fixtureMetrics(cases));
    metrics.addAll(goldCaptureMetrics(cases));
    metrics.addAll(hardNegativeMetrics(cases));
    metrics.addAll(ambiguityMetrics(cases));
    metrics.addAll(goldQueryMetrics(cases));
    metrics.add(reopenMetrics(cases));
    double repairPass =
        repairs.isEmpty()
            ? 0.0
            : repairs.stream().filter(RepairScore::passed).count() / (double) repairs.size();
    List<String> failures = new ArrayList<>();
    failures.addAll(thresholdFailures(metrics, repairPass));
    return new Report(cases, metrics, repairs, repairPass, liveModelSlice, failures);
  }

  static String render(Report report) {
    StringBuilder text = new StringBuilder();
    text.append("Multilingual mapping capture evaluation\n");
    text.append("Live-model slice: ").append(report.liveModelSlice()).append('\n');
    text.append("Compiler-repair pass rate: ")
        .append(format(report.compilerRepairPassRate()))
        .append('\n');
    for (RepairScore repair : report.repairs()) {
      text.append("- repair ")
          .append(repair.id())
          .append(" passed=")
          .append(repair.passed())
          .append(" calls=")
          .append(repair.agentCalls())
          .append(" secondLoop=")
          .append(repair.secondLoop())
          .append(" ")
          .append(repair.detail())
          .append('\n');
    }
    for (SubsetMetrics metrics : report.metrics()) {
      text.append(metrics.subset())
          .append('/')
          .append(metrics.language())
          .append(" n=")
          .append(metrics.cases())
          .append(" exact=")
          .append(format(metrics.exactState()))
          .append(" intentP=")
          .append(format(metrics.intentPrecision()))
          .append(" intentR=")
          .append(format(metrics.intentRecall()))
          .append(" ruleP=")
          .append(format(metrics.rulePrecision()))
          .append(" ruleR=")
          .append(format(metrics.ruleRecall()))
          .append(" clarify=")
          .append(format(metrics.clarificationRate()))
          .append(" create=")
          .append(format(metrics.mappingIntentCreationRate()))
          .append(" unsafe=")
          .append(format(metrics.unsafeEditRate()))
          .append(" factual=")
          .append(format(metrics.exactFactualMatch()))
          .append(" briefChg=")
          .append(format(metrics.briefChangeRate()))
          .append(" approvalChg=")
          .append(format(metrics.approvalChangeRate()))
          .append(" planChg=")
          .append(format(metrics.planChangeRate()))
          .append(" pass=")
          .append(format(metrics.passRate()))
          .append('\n');
    }
    if (report.failures().isEmpty()) {
      text.append("Thresholds: PASS\n");
    } else {
      text.append("Thresholds: FAIL\n");
      for (String failure : report.failures()) {
        text.append("- ").append(failure).append('\n');
      }
    }
    return text.toString();
  }

  private static List<String> thresholdFailures(List<SubsetMetrics> metrics, double repairPass) {
    List<String> failures = new ArrayList<>();
    for (SubsetMetrics row : metrics) {
      switch (row.subset()) {
        case FIXTURE -> {
          if (row.exactState() < 1.0) {
            failures.add(row.subset() + "/" + row.language() + " exact state " + format(row.exactState())
                + " < 1.00");
          }
        }
        case GOLD_CAPTURE -> {
          require(failures, row, "exact", row.exactState(), 0.90);
          require(failures, row, "intent precision", row.intentPrecision(), 0.90);
          require(failures, row, "intent recall", row.intentRecall(), 0.90);
          require(failures, row, "rule precision", row.rulePrecision(), 0.85);
          require(failures, row, "rule recall", row.ruleRecall(), 0.85);
          if (row.clarificationRate() > 0.10) {
            failures.add(
                row.subset()
                    + "/"
                    + row.language()
                    + " clarification rate "
                    + format(row.clarificationRate())
                    + " > 0.10");
          }
        }
        case HARD_NEGATIVE -> {
          if (row.mappingIntentCreationRate() != 0.0) {
            failures.add(
                row.subset()
                    + "/"
                    + row.language()
                    + " mapping-intent creation rate "
                    + format(row.mappingIntentCreationRate())
                    + " != 0");
          }
        }
        case AMBIGUITY -> {
          if (row.unsafeEditRate() != 0.0) {
            failures.add(
                row.subset()
                    + "/"
                    + row.language()
                    + " unsafe-edit rate "
                    + format(row.unsafeEditRate())
                    + " != 0");
          }
        }
        case GOLD_QUERY -> {
          require(failures, row, "exact factual match", row.exactFactualMatch(), 0.90);
          if (row.briefChangeRate() != 0.0
              || row.approvalChangeRate() != 0.0
              || row.planChangeRate() != 0.0) {
            failures.add(
                row.subset()
                    + "/"
                    + row.language()
                    + " brief/approval/plan change rate is not 0");
          }
        }
        case REOPEN -> {
          if (row.passRate() != 1.0) {
            failures.add("REOPEN pass rate " + format(row.passRate()) + " < 1.00");
          }
        }
      }
    }
    if (repairPass != 1.0) {
      failures.add("COMPILER_REPAIR pass rate " + format(repairPass) + " < 1.00");
    }
    return failures;
  }

  private static void require(
      List<String> failures, SubsetMetrics row, String name, double actual, double min) {
    if (actual < min) {
      failures.add(
          row.subset() + "/" + row.language() + " " + name + " " + format(actual) + " < " + format(min));
    }
  }

  private static List<SubsetMetrics> fixtureMetrics(List<CaseScore> cases) {
    List<SubsetMetrics> rows = new ArrayList<>();
    for (Language language : Language.values()) {
      List<CaseScore> slice = filter(cases, Subset.FIXTURE, language);
      if (slice.isEmpty()) {
        continue;
      }
      rows.add(stateMetrics(Subset.FIXTURE, language, slice));
    }
    return rows;
  }

  private static List<SubsetMetrics> goldCaptureMetrics(List<CaseScore> cases) {
    List<SubsetMetrics> rows = new ArrayList<>();
    for (Language language : Language.values()) {
      List<CaseScore> slice = filter(cases, Subset.GOLD_CAPTURE, language);
      if (slice.isEmpty()) {
        continue;
      }
      rows.add(stateMetrics(Subset.GOLD_CAPTURE, language, slice));
    }
    return rows;
  }

  private static List<SubsetMetrics> goldQueryMetrics(List<CaseScore> cases) {
    List<SubsetMetrics> rows = new ArrayList<>();
    for (Language language : Language.values()) {
      List<CaseScore> slice = filter(cases, Subset.GOLD_QUERY, language);
      if (slice.isEmpty()) {
        continue;
      }
      int n = slice.size();
      long factual = slice.stream().filter(CaseScore::factualMatch).count();
      long brief = slice.stream().filter(CaseScore::briefChanged).count();
      long approval = slice.stream().filter(CaseScore::approvalChanged).count();
      long plan = slice.stream().filter(CaseScore::planChanged).count();
      rows.add(
          new SubsetMetrics(
              Subset.GOLD_QUERY,
              language,
              n,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              ratio(factual, n),
              ratio(brief, n),
              ratio(approval, n),
              ratio(plan, n),
              ratio(factual, n)));
    }
    return rows;
  }

  private static List<SubsetMetrics> hardNegativeMetrics(List<CaseScore> cases) {
    List<SubsetMetrics> rows = new ArrayList<>();
    for (Language language : Language.values()) {
      List<CaseScore> slice = filter(cases, Subset.HARD_NEGATIVE, language);
      if (slice.isEmpty()) {
        continue;
      }
      int n = slice.size();
      long created = slice.stream().filter(CaseScore::createdIntent).count();
      rows.add(
          new SubsetMetrics(
              Subset.HARD_NEGATIVE,
              language,
              n,
              0,
              0,
              0,
              0,
              0,
              0,
              ratio(created, n),
              0,
              0,
              0,
              0,
              0,
              created == 0 ? 1.0 : 0.0));
    }
    return rows;
  }

  private static List<SubsetMetrics> ambiguityMetrics(List<CaseScore> cases) {
    List<SubsetMetrics> rows = new ArrayList<>();
    for (Language language : Language.values()) {
      List<CaseScore> slice = filter(cases, Subset.AMBIGUITY, language);
      if (slice.isEmpty()) {
        continue;
      }
      int n = slice.size();
      long unsafe = slice.stream().filter(CaseScore::unsafeEdit).count();
      rows.add(
          new SubsetMetrics(
              Subset.AMBIGUITY,
              language,
              n,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              ratio(unsafe, n),
              0,
              0,
              0,
              0,
              unsafe == 0 ? 1.0 : 0.0));
    }
    return rows;
  }

  private static SubsetMetrics reopenMetrics(List<CaseScore> cases) {
    List<CaseScore> slice = filter(cases, Subset.REOPEN, null);
    int n = slice.size();
    long passed = slice.stream().filter(CaseScore::reopenPassed).count();
    return new SubsetMetrics(
        Subset.REOPEN,
        Language.ENGLISH,
        n,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        ratio(passed, n));
  }

  private static SubsetMetrics stateMetrics(Subset subset, Language language, List<CaseScore> slice) {
    int n = slice.size();
    long exact = slice.stream().filter(CaseScore::exactState).count();
    long clarify = slice.stream().filter(CaseScore::clarification).count();
    PrecisionRecall intents = precisionRecall(slice, true);
    PrecisionRecall rules = precisionRecall(slice, false);
    return new SubsetMetrics(
        subset,
        language,
        n,
        ratio(exact, n),
        intents.precision(),
        intents.recall(),
        rules.precision(),
        rules.recall(),
        ratio(clarify, n),
        0,
        0,
        0,
        0,
        0,
        0,
        ratio(exact, n));
  }

  private record PrecisionRecall(double precision, double recall) {}

  private static PrecisionRecall precisionRecall(List<CaseScore> slice, boolean intents) {
    int predicted = 0;
    int gold = 0;
    int hit = 0;
    for (CaseScore score : slice) {
      Set<String> predictedKeys = intents ? score.actualIntents() : score.actualRules();
      Set<String> goldKeys = intents ? score.goldIntents() : score.goldRules();
      predicted += predictedKeys.size();
      gold += goldKeys.size();
      for (String key : predictedKeys) {
        if (goldKeys.contains(key)) {
          hit++;
        }
      }
    }
    return new PrecisionRecall(ratio(hit, predicted), ratio(hit, gold));
  }

  private static List<CaseScore> filter(List<CaseScore> cases, Subset subset, Language language) {
    List<CaseScore> slice = new ArrayList<>();
    for (CaseScore score : cases) {
      if (score.subset() != subset) {
        continue;
      }
      if (language != null && score.language() != language) {
        continue;
      }
      slice.add(score);
    }
    return slice;
  }

  static List<String> normalizedState(RequirementBrief brief) {
    if (brief == null) {
      return List.of();
    }
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

  private static Set<String> intentKeys(RequirementBrief brief) {
    Set<String> keys = new LinkedHashSet<>();
    if (brief == null) {
      return keys;
    }
    for (MappingIntent intent : brief.mappingIntents()) {
      keys.add(intent.sourceRef() + "->" + intent.targetRef());
    }
    return keys;
  }

  private static Set<String> ruleKeys(RequirementBrief brief) {
    Set<String> keys = new LinkedHashSet<>();
    if (brief == null) {
      return keys;
    }
    for (MappingIntent intent : brief.mappingIntents()) {
      for (MappingIntentRule rule : intent.rules()) {
        if (rule.status() == MappingRuleStatus.UNRESOLVED) {
          continue;
        }
        keys.add(
            intent.sourceRef()
                + "->"
                + intent.targetRef()
                + ":"
                + MappingContract.canonicalPath(rule.sourcePath())
                + ">"
                + MappingContract.canonicalPath(rule.targetPath())
                + "="
                + String.valueOf(rule.expression()));
      }
    }
    return keys;
  }

  private static boolean sameFacts(MappingQueryAnswer expected, MappingQueryAnswer actual) {
    if (expected == null || actual == null) {
      return false;
    }
    if (expected.matchFound() != actual.matchFound()) {
      return false;
    }
    if (!expected.language().equals(actual.language())) {
      return false;
    }
    if (!expected.unresolvedTargetPaths().equals(actual.unresolvedTargetPaths())) {
      return false;
    }
    if (expected.rules().size() != actual.rules().size()) {
      return false;
    }
    Map<String, RuleFact> actualRules = new LinkedHashMap<>();
    for (RuleFact rule : actual.rules()) {
      actualRules.put(ruleKey(rule), rule);
    }
    for (RuleFact rule : expected.rules()) {
      RuleFact found = actualRules.get(ruleKey(rule));
      if (found == null
          || !Objects.equals(rule.mappingIntentId(), found.mappingIntentId())
          || !Objects.equals(rule.sourceRef(), found.sourceRef())
          || !Objects.equals(rule.targetRef(), found.targetRef())
          || !Objects.equals(rule.status(), found.status())
          || !Objects.equals(rule.expression(), found.expression())) {
        return false;
      }
    }
    if (expected.transitions().size() != actual.transitions().size()) {
      return false;
    }
    Map<String, TransitionFact> actualTransitions = new LinkedHashMap<>();
    for (TransitionFact transition : actual.transitions()) {
      actualTransitions.put(transitionKey(transition), transition);
    }
    for (TransitionFact transition : expected.transitions()) {
      if (!actualTransitions.containsKey(transitionKey(transition))) {
        return false;
      }
    }
    return true;
  }

  private static String ruleKey(RuleFact rule) {
    return rule.mappingIntentId()
        + ":"
        + MappingContract.canonicalPath(rule.sourcePath())
        + ">"
        + MappingContract.canonicalPath(rule.targetPath());
  }

  private static String transitionKey(TransitionFact transition) {
    return transition.sourceRef()
        + "->"
        + transition.targetRef()
        + ":"
        + transition.passThrough()
        + ":"
        + transition.mappingIntentId();
  }

  private static boolean planImpact(
      ConversationCase conversationCase, MappingTurnApplication application) {
    DesignExecutionPlan plan = conversationCase.plan();
    if (plan == null) {
      return false;
    }
    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(
            conversationCase.seed(), application.brief(), plan);
    return impact.briefReopened() || !impact.invalidatedPlanStepIds().isEmpty();
  }

  private static List<String> allStepIds(DesignExecutionPlan plan) {
    List<String> ids = new ArrayList<>();
    for (DesignExecutionPlan.Step step : plan.steps()) {
      ids.add(step.stepId());
    }
    return List.copyOf(ids);
  }

  private static double ratio(long hit, int total) {
    if (total <= 0) {
      return 0.0;
    }
    return hit / (double) total;
  }

  private static String format(double value) {
    return String.format(java.util.Locale.US, "%.2f", value);
  }
}
