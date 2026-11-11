package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner.Plan;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner.Reason;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner.Readiness;

class WorkFillingReadinessTest {

  private final WorkTaskPlanner planner = new WorkTaskPlanner();

  @Test
  void acceptedLocalOutlineWithEvidencedNoMappingIsReady() {
    Plan plan = planner.plan(accepted(localNoMapping()));

    assertEquals(Readiness.Status.READY_FOR_PRESENTATION, plan.readiness().status());
    assertNull(plan.selected());
    assertTrue(plan.readiness().questionIds().isEmpty());
    assertTrue(plan.readiness().defects().isEmpty());
  }

  @Test
  void unevidencedEmptyMappingPreventsReadiness() {
    Plan plan = planner.plan(accepted(serviceTransfer(List.of(), MappingDecision.UNSPECIFIED)));

    assertEquals(Readiness.Status.HALTED, plan.readiness().status());
    assertNull(plan.selected());
    assertEquals(Reason.UNEVIDENCED_MAPPING, defect(plan, "map-transfer:to-request").reason());
    assertEquals(
        "Transfer to-request has no rules and no NO_MAPPING decision.",
        defect(plan, "map-transfer:to-request").evidence());
  }

  @Test
  void evidencedNoMappingDecisionIsReady() {
    Plan plan = planner.plan(accepted(serviceTransfer(List.of(), MappingDecision.NO_MAPPING)));

    assertEquals(Readiness.Status.READY_FOR_PRESENTATION, plan.readiness().status());
  }

  @Test
  void openQuestionPreventsPresentationAfterIndependentWorkIsFinished() {
    ChainWorkDocument accepted = accepted(serviceTransfer(List.of(WorkPlanningDocuments.rule("rule-1", "trigger", "call")), MappingDecision.UNSPECIFIED));
    WorkQuestion question =
        new WorkQuestion(
            "q-open",
            "field",
            "Which field is the order id?",
            List.of(WorkPlanningDocuments.SOURCE_ID),
            "map-transfer:to-request",
            QuestionSubject.unspecified(),
            List.of("to-request"),
            List.of(),
            QuestionResolution.OPEN);
    ChainWorkDocument waiting =
        WorkPlanningDocuments.replaceProgress(
            accepted,
            new WorkProgress(
                accepted.progress().tasks(),
                List.of(),
                List.of(question),
                "",
                List.of(),
                List.of()));

    Plan plan = planner.plan(waiting);

    assertEquals(Readiness.Status.WAITING_FOR_INPUT, plan.readiness().status());
    assertEquals(List.of("q-open"), plan.readiness().questionIds());
    assertNull(plan.selected());
  }

  @Test
  void coverageGapPreventsPresentation() {
    LogicalStep format =
        WorkPlanningDocuments.step(
            "format",
            StepKind.LOCAL,
            null,
            new StepData(List.of(), List.of(), DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    ChainWorkDocument document =
        WorkPlanningDocuments.document("doc-1", List.of(format), List.of(), WorkProgress.empty());

    Plan plan = planner.plan(accepted(document));

    assertEquals(Readiness.Status.HALTED, plan.readiness().status());
    assertEquals(Reason.COVERAGE_GAP, defect(plan, "define-transfers:format").reason());
    assertEquals(
        "Step format is missing coverage for requirement req-1.",
        defect(plan, "define-transfers:format").evidence());
  }

  @Test
  void unresolvedRetainedValuePreventsPresentation() {
    RetainedValue kept = WorkPlanningDocuments.unresolved("kept-order", "trigger");
    LogicalStep trigger =
        WorkPlanningDocuments.step(
            "trigger",
            StepKind.TRIGGER,
            WorkPlanningDocuments.binding("receive", "1.0.0"),
            new StepData(List.of(), List.of(kept), DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "trigger",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(WorkPlanningDocuments.rule("rule-1", "trigger", "call")),
                        List.of("kept-order"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(
                    WorkPlanningDocuments.REQUIREMENT_ID,
                    WorkPlanningDocuments.PASSAGE_ID,
                    "to-request",
                    CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    ChainWorkDocument document =
        WorkPlanningDocuments.document(
            "doc-1",
            List.of(trigger, call),
            List.of(WorkPlanningDocuments.connection("c1", "trigger", "success", "call")),
            WorkProgress.empty());

    Plan plan = planner.plan(accepted(document));

    assertEquals(Readiness.Status.HALTED, plan.readiness().status());
    assertEquals(Reason.UNRESOLVED_INPUT, defect(plan, "map-transfer:to-request").reason());
    assertEquals(
        "Transfer to-request requires retained value kept-order, and that value is unresolved.",
        defect(plan, "map-transfer:to-request").evidence());
  }

  @Test
  void namedRuleRepairStaysPendingUntilThatRepairIsAccepted() {
    ChainWorkDocument accepted =
        accepted(serviceTransfer(List.of(WorkPlanningDocuments.rule("rule-1", "trigger", "call")), MappingDecision.UNSPECIFIED));
    WorkFinding finding =
        new WorkFinding("finding-1", "rule-1", "WRONG_MAPPING", "Priority dropped the low branch.", List.of(WorkPlanningDocuments.SOURCE_ID), "");
    ChainWorkDocument withFinding =
        WorkPlanningDocuments.replaceProgress(
            accepted,
            new WorkProgress(
                accepted.progress().tasks(),
                List.of(finding),
                List.of(),
                "",
                List.of(),
                List.of()));

    Plan pending = planner.plan(withFinding);

    assertEquals("repair-rule:rule-1", pending.selected().taskKey());
    assertEquals(WorkTaskKind.REPAIR_RULE, pending.selected().kind());
    assertEquals(Readiness.Status.WORK_REMAINING, pending.readiness().status());

    ChainWorkDocument repaired = WorkPlanningDocuments.accept(withFinding, task -> task.kind() == WorkTaskKind.REPAIR_RULE);
    Plan done = planner.plan(repaired);
    assertEquals(Readiness.Status.READY_FOR_PRESENTATION, done.readiness().status());
  }

  @Test
  void nonRuleFindingHaltsPresentation() {
    ChainWorkDocument accepted =
        accepted(serviceTransfer(List.of(WorkPlanningDocuments.rule("rule-1", "trigger", "call")), MappingDecision.UNSPECIFIED));
    WorkFinding finding =
        new WorkFinding("finding-1", "call", "WRONG_ACTION", "The call is the wrong action.", List.of(WorkPlanningDocuments.SOURCE_ID), "");
    ChainWorkDocument withFinding =
        WorkPlanningDocuments.replaceProgress(
            accepted,
            new WorkProgress(accepted.progress().tasks(), List.of(finding), List.of(), "", List.of(), List.of()));

    Plan plan = planner.plan(withFinding);

    assertEquals(Readiness.Status.HALTED, plan.readiness().status());
    assertNull(plan.selected());
    assertEquals(Reason.ACTIVE_FINDING, defect(plan, "select-operation:call").reason());
    assertEquals(
        "Finding finding-1 records an open defect on call.",
        defect(plan, "select-operation:call").evidence());
  }

  private ChainWorkDocument localNoMapping() {
    LogicalStep format =
        WorkPlanningDocuments.step(
            "format",
            StepKind.LOCAL,
            null,
            new StepData(
                List.of(),
                List.of(),
                WorkPlanningDocuments.covered(
                    WorkPlanningDocuments.REQUIREMENT_ID,
                    WorkPlanningDocuments.PASSAGE_ID,
                    "",
                    CoverageDisposition.NO_MAPPING)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document("doc-1", List.of(format), List.of(), WorkProgress.empty());
  }

  private ChainWorkDocument serviceTransfer(List<MappingRule> rules, MappingDecision decision) {
    LogicalStep trigger =
        WorkPlanningDocuments.step(
            "trigger", StepKind.TRIGGER, WorkPlanningDocuments.binding("receive", "1.0.0"), StepData.empty());
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "trigger",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        decision,
                        rules,
                        List.of(),
                        decision == MappingDecision.NO_MAPPING
                            ? new String[0]
                            : new String[] {WorkPlanningDocuments.REQUIREMENT_ID})),
                List.of(),
                WorkPlanningDocuments.covered(
                    WorkPlanningDocuments.REQUIREMENT_ID,
                    WorkPlanningDocuments.PASSAGE_ID,
                    decision == MappingDecision.NO_MAPPING ? "" : "to-request",
                    decision == MappingDecision.NO_MAPPING ? CoverageDisposition.NO_MAPPING : CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(trigger, call),
        List.of(WorkPlanningDocuments.connection("c1", "trigger", "success", "call")),
        WorkProgress.empty());
  }

  private ChainWorkDocument accepted(ChainWorkDocument document) {
    return WorkPlanningDocuments.accept(document, task -> true);
  }

  private static WorkTaskPlanner.Block defect(Plan plan, String taskKey) {
    return plan.readiness().defects().stream().filter(item -> item.taskKey().equals(taskKey)).findFirst().orElseThrow();
  }
}
