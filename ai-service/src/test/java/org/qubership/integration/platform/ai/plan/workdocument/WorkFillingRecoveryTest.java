package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Technical retry, stale output, a real second model call, and a contract recheck.
 * The driver calls only advance.
 */
class WorkFillingRecoveryTest {

  @Test
  void staleOutputRetriesWithoutASecondModelCallOrAcceptedSteps() throws Exception {
    FillingWorld world = FillingWorld.start("run-stale");
    world.model.duringComplete =
        () -> {
          world.model.duringComplete = null;
          ChainWorkDocument current = world.document();
          WorkProgress progress = current.progress();
          ChainWorkDocument changed =
              new ChainWorkDocument(
                  current.schemaVersion(),
                  current.documentId(),
                  current.sources(),
                  current.requirements(),
                  current.flow(),
                  new WorkProgress(
                      progress.tasks(),
                      progress.findings(),
                      progress.questions(),
                      "concurrent-answer",
                      progress.derivedResultReferences(),
                      progress.recheckStages()));
          world.documents.commitRecoveredDocument(
              world.runId,
              changed,
              "concurrent-note",
              "stale-hash",
              "concurrent-clarification",
              "LOGICAL_FLOW",
              null);
        };
    FillingResult stale = world.filling.advance(world.runId, "stale-1");
    assertEquals(FillingResult.Action.RETRY_CURRENT, stale.action(), stale.toString());
    assertEquals(0L, stale.retryDelayMs());
    assertTrue(stale.reasons().contains("stale-result"));
    assertEquals(1, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
    assertTrue(world.document().flow().steps().isEmpty());
    assertTrue(stale.questionIds().isEmpty());
  }

  @Test
  void correctivePublishRemovesOnlyTheResolvedFinding() throws Exception {
    FillingWorld world = FillingWorld.start("run-finding");
    world.model.inject(
        WorkTaskKind.LOGICAL_DESIGN, 1, FillingWorld.ScriptedWorkModel.synchronousReceive("src-om"));
    FillingResult detected = world.advance(null);
    assertTrue(detected.reasons().contains("SYNCHRONOUS_RESULT"), detected.toString());
    ChainWorkDocument current = world.document();
    assertFalse(current.progress().findings().isEmpty(), detected.toString());
    WorkFinding open = current.progress().findings().get(0);
    List<WorkFinding> findings = new ArrayList<>(current.progress().findings());
    findings.add(
        new WorkFinding(
            "finding-unrelated",
            open.recordRef(),
            "UNRELATED_GAP",
            "A second defect remains.",
            List.of("src-om"),
            "other"));
    world.replaceProgress(current.progress().tasks(), findings, "plant-finding");
    FillingResult repaired = world.advance(null);
    assertEquals(FillingResult.Action.HALTED, repaired.action(), repaired.toString());
    assertTrue(repaired.reasons().contains("PREPARED"), repaired.toString());
    assertTrue(
        repaired.reasons().contains("A structural defect or unmet dependency remains."), repaired.toString());
    boolean unrelated = false;
    boolean resolvedGone = true;
    for (WorkFinding finding : world.document().progress().findings()) {
      if ("UNRELATED_GAP".equals(finding.issueCategory())) {
        unrelated = true;
      }
      if ("SYNCHRONOUS_RESULT".equals(finding.issueCategory()) && open.recordRef().equals(finding.recordRef())) {
        resolvedGone = false;
      }
    }
    assertTrue(unrelated, world.document().progress().findings().toString());
    assertTrue(resolvedGone, world.document().progress().findings().toString());
  }

  @Test
  void networkFailureRetriesWithTheConfiguredDelayAndNoBusinessQuestion() throws Exception {
    FillingWorld world = FillingWorld.start("run-network");
    world.model.inject(WorkTaskKind.LOGICAL_DESIGN, 1, "CONNECT");
    FillingResult retry = world.advance(null);
    assertEquals(FillingResult.Action.RETRY_CURRENT, retry.action(), retry.toString());
    assertEquals(250L, retry.retryDelayMs());
    assertTrue(retry.questionIds().isEmpty());
    var run = world.runs.load(world.runId).orElseThrow();
    assertTrue(world.runs.uncertainProviderDeliveries(run).contains("advance-1:1"));
    assertTrue(world.runs.confirmedProviderDeliveries(run).isEmpty());
    FillingResult next = world.advance(null);
    assertEquals(FillingResult.Action.ADVANCED, next.action(), next.toString());
    assertEquals(2, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
  }

  @Test
  void rejectedFieldReferenceInvokesTheModelAgain() throws Exception {
    FillingWorld world = FillingWorld.start("run-bad-ref");
    world.model.badMappingField = true;
    List<String> trace = new ArrayList<>();
    String rejectedTask = "";
    String revisionBeforeRepair = "";
    for (int step = 0; step < 30 && (rejectedTask.isBlank() || callsFor(world, rejectedTask) < 2); step++) {
      int before = world.model.count(WorkTaskKind.MAP_TRANSFER);
      FillingResult result = world.advance(trace);
      if (world.model.count(WorkTaskKind.MAP_TRANSFER) == 1 && before == 0) {
        rejectedTask = result.taskId();
        revisionBeforeRepair = result.documentRevision();
        assertTrue(result.reasons().contains("MALFORMED_REFERENCE"), result.toString());
        assertEquals(1, world.repairCharges());
      }
    }
    assertEquals(2, callsFor(world, rejectedTask), trace.toString());
    assertNotEquals(revisionBeforeRepair, world.documents.read(world.runId).revision());
    assertTrue(acceptedBehavior(world.document()), trace.toString());
  }

  @Test
  void changedContractRechecksTheMappingAndLeavesTheOutlineCallCount() throws Exception {
    FillingWorld world = FillingWorld.start("run-contract");
    List<String> trace = new ArrayList<>();
    while (world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) < 1
        || world.model.count(WorkTaskKind.MAP_TRANSFER) > 0) {
      FillingResult result = world.advance(trace);
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    world.model.routingDefectCategory = "WRONG_OPERATION";
    FillingResult detected = world.advance(trace);
    assertTrue(detected.reasons().contains("WRONG_OPERATION"), detected.toString());
    assertEquals(1, world.model.count(WorkTaskKind.MAP_TRANSFER));
    world.catalog.version("2");
    int selects = world.model.count(WorkTaskKind.SELECT_OPERATION);
    FillingResult corrected = world.advance(trace);
    assertEquals(FillingResult.Action.ADVANCED, corrected.action(), corrected.toString());
    assertEquals(selects + 1, world.model.count(WorkTaskKind.SELECT_OPERATION));
    boolean versionTwo = false;
    for (LogicalStep step : world.document().flow().steps()) {
      if (step.binding() != null && "2".equals(step.binding().version())) {
        versionTwo = true;
      }
    }
    assertTrue(versionTwo, trace.toString());
    String mappingTask = detected.taskId();
    while (callsFor(world, mappingTask) < 2) {
      FillingResult result = world.advance(trace);
      if (result.action() != FillingResult.Action.ADVANCED) {
        break;
      }
    }
    assertEquals(2, callsFor(world, mappingTask), trace.toString());
    assertEquals(outlines, world.model.count(WorkTaskKind.DEFINE_TRANSFERS), trace.toString());
  }

  private static boolean acceptedBehavior(ChainWorkDocument document) {
    String rules = WorkDocumentFillingTest.behaviors(document);
    return rules.contains("formatted fallback")
        || rules.contains("failure code")
        || rules.contains("processInstanceId");
  }

  private static int callsFor(FillingWorld world, String taskId) {
    int count = 0;
    for (String call : world.model.calls()) {
      if (call.endsWith(" " + taskId)) {
        count++;
      }
    }
    return count;
  }
}
