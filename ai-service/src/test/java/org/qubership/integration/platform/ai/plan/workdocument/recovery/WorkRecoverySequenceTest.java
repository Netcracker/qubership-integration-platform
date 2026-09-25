package org.qubership.integration.platform.ai.plan.workdocument.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkRecoverySequenceTest {

  private static final String RUN = "run-recovery";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private WorkDocumentService documents;
  private ProductPipelineRunStore runs;
  private WorkRecovery recovery;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    runs = new ProductPipelineRunStore(blobs, JSON, clock);
    documents = new WorkDocumentService(runs, artifacts, JSON);
    runs.create(
        new RunSnapshot(
            RUN,
            "conversation-recovery",
            1L,
            RunStatus.RUNNING,
            "DATA_BEHAVIOR",
            List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        RUN,
        new WorkDocumentState("pending", JSON.readValue(DOCUMENT, ChainWorkDocument.class)),
        "cmd-seed",
        new WorkRepairBudget(3));
    recovery = WorkRecovery.create(documents, runs);
  }

  @Test
  void mappingFindingReturnsToBindingAndKeepsMappingRulesForRecheck() {
    WorkRecovery.Result routed =
        recovery.route(
            RUN,
            WorkRecovery.Defect.of(
                "create",
                "WRONG_OPERATION",
                "binding",
                "The mapped operation does not create the task.",
                "src-om"),
            "cmd-binding");

    assertEquals(WorkRecovery.causeKey(RUN, "create", "WRONG_OPERATION", "binding"), routed.causeKey());
    assertFalse(routed.causeKey().contains("The mapped operation"));
    assertFalse(routed.causeKey().contains("DATA_BEHAVIOR"));
    assertEquals(WorkStage.SERVICES, routed.owner());
    assertTrue(routed.dispatched());
    assertEquals(RecoveryAction.REGENERATE_ARTIFACT, routed.decision().action());
    assertEquals("NEEDS_RECHECK", taskState("DATA_BEHAVIOR"));
    assertEquals("PENDING", taskState("SERVICES"));
    assertEquals("ACCEPTED", taskState("LOGICAL_FLOW"));
    assertTrue(recheckStages().contains("DATA_BEHAVIOR"));
    assertFalse(recheckStages().contains("SERVICES"));
    assertEquals(List.of("rule-subject", "rule-priority"), ruleIds());
    assertEquals(1, repairCharges());
  }

  @Test
  void oneCauseSurvivesBindingAndLogicalHopsWhenTheWordingChanges() {
    WorkRecovery.Result mapping =
        recovery.route(
            RUN,
            WorkRecovery.Defect.of(
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "Priority targets the wrong operation.",
                "src-om"),
            "cmd-map");
    WorkRecovery.Result binding =
        recovery.route(
            RUN,
            new WorkRecovery.Defect(
                "",
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "The selected operation is not createTask.",
                List.of("src-om"),
                "create",
                "binding"),
            "cmd-bind");
    WorkRecovery.Result logical =
        recovery.route(
            RUN,
            new WorkRecovery.Defect(
                mapping.findingId(),
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "The flow should not call that API.",
                List.of("src-om"),
                "req-flow",
                "text"),
            "cmd-flow");

    assertEquals(mapping.causeKey(), binding.causeKey());
    assertEquals(mapping.causeKey(), logical.causeKey());
    assertEquals(WorkStage.DATA_BEHAVIOR, mapping.owner());
    assertEquals(WorkStage.SERVICES, binding.owner());
    assertEquals(WorkStage.LOGICAL_FLOW, logical.owner());
    assertEquals(0, logical.repairsRemaining());
    assertEquals("NEEDS_RECHECK", taskState("SERVICES"));
    assertEquals("NEEDS_RECHECK", taskState("DATA_BEHAVIOR"));
    assertEquals("PENDING", taskState("LOGICAL_FLOW"));
    assertEquals(List.of("rule-subject", "rule-priority"), ruleIds());
    assertEquals(3, repairCharges());
    assertEquals("WRONG_OPERATION", findingCategory(mapping.findingId()));
  }

  @Test
  void fourthCorrectiveInvocationIsBlockedAndTheDocumentRemains() {
    String findingId = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      WorkRecovery.Result routed =
          recovery.route(
              RUN,
              new WorkRecovery.Defect(
                  findingId == null ? "" : findingId,
                  "rule-priority",
                  "WRONG_OPERATION",
                  "behavior",
                  "Wording " + attempt,
                  List.of("src-om"),
                  "",
                  ""),
              "cmd-" + attempt);
      findingId = routed.findingId();
      assertTrue(routed.dispatched());
    }

    WorkRecovery.Result blocked =
        recovery.route(
            RUN,
            new WorkRecovery.Defect(
                findingId,
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "Wording 4",
                List.of("src-om"),
                "",
                ""),
            "cmd-4");

    assertFalse(blocked.dispatched());
    assertTrue(blocked.exhausted());
    assertEquals(0, blocked.repairsRemaining());
    assertEquals(3, repairCharges());
    assertEquals(List.of("rule-subject", "rule-priority"), ruleIds());
    assertEquals("WRONG_OPERATION", findingCategory(findingId));
    assertTrue(nextAction().contains("spent"));
    assertTrue(nextAction().contains("rule-priority"));
    documents.read(RUN);
  }

  @Test
  void restartAndDuplicateDeliveryDoNotRestoreOrDoubleChargeTheAllowance() {
    WorkRecovery.Defect defect =
        WorkRecovery.Defect.of(
            "rule-priority", "WRONG_OPERATION", "behavior", "Priority is wrong.", "src-om");
    WorkRecovery.Result first = recovery.route(RUN, defect, "cmd-once");
    WorkRecovery.Result duplicate = recovery.route(RUN, defect, "cmd-once");

    assertEquals(first.causeKey(), duplicate.causeKey());
    assertEquals(first.repairsRemaining(), duplicate.repairsRemaining());
    assertEquals(2, duplicate.repairsRemaining());
    assertEquals(1, repairCharges());

    WorkRecovery restarted = WorkRecovery.create(documents, runs);
    assertEquals(first.causeKey(), restarted.causeKey(RUN, first.findingId()));
    assertEquals(2, restarted.repairsRemaining(RUN, first.causeKey()));

    WorkRecovery.Result afterRestart =
        restarted.route(
            RUN,
            WorkRecovery.Defect.of(
                "rule-priority", "WRONG_OPERATION", "behavior", "Priority is still wrong.", "src-om"),
            "cmd-after-restart");
    assertEquals(first.causeKey(), afterRestart.causeKey());
    assertEquals(1, afterRestart.repairsRemaining());
    assertEquals(2, repairCharges());
  }

  @Test
  void aDifferentRequirementHasItsOwnCauseAndAnOpenFindingCannotBeRenamed() {
    WorkRecovery.Result first =
        recovery.route(
            RUN,
            WorkRecovery.Defect.of(
                "req-flow", "WRONG_OPERATION", "text", "The call is wrong.", "src-om"),
            "cmd-first");
    WorkRecovery.Result second =
        recovery.route(
            RUN,
            WorkRecovery.Defect.of(
                "req-other", "MISSING_FACT", "text", "Which order id should be kept?", "src-om"),
            "cmd-second");

    assertNotEquals(first.causeKey(), second.causeKey());
    assertEquals(2, first.repairsRemaining());
    assertEquals(2, recovery.repairsRemaining(RUN, first.causeKey()));
    assertEquals(2, second.repairsRemaining());
    assertEquals("WRONG_OPERATION", findingCategory(first.findingId()));
    assertEquals("MISSING_FACT", findingCategory(second.findingId()));

    WorkDocumentRejectedException rename =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                recovery.route(
                    RUN,
                    new WorkRecovery.Defect(
                        first.findingId(),
                        "req-flow",
                        "RENAMED",
                        "text",
                        "The call is wrong.",
                        List.of("src-om"),
                        "",
                        ""),
                    "cmd-rename"));
    assertEquals("SILENT_RENAME", rename.code());
    assertEquals("WRONG_OPERATION", findingCategory(first.findingId()));
    assertEquals(2, recovery.repairsRemaining(RUN, first.causeKey()));
    assertEquals(2, repairCharges());
  }

  @Test
  void networkFailureRetriesWithoutAskingAboutRequirements() {
    WorkRecovery.Result technical =
        recovery.technicalRetry(RUN, new ConnectException("connection reset"), "cmd-net", 2);

    assertEquals(RecoveryCauseClass.TECHNICAL_FAILURE, technical.decision().causeClass());
    assertEquals(RecoveryAction.RETRY_OPERATION, technical.decision().action());
    assertTrue(technical.dispatched());
    assertEquals(0, document().path("progress").path("questions").size());
    assertEquals(0, repairCharges());
    assertTrue(document().path("progress").path("findings").isEmpty());
  }

  @Test
  void nestedToolRetriesStopAtTheLedgerInsteadOfAPrivateCounter() {
    int accepted = 0;
    for (int attempt = 0; attempt < 5; attempt++) {
      if (recovery.nestedToolRetry(RUN, new ConnectException("connection reset"), "nest-" + attempt, 2)) {
        accepted++;
      }
    }

    assertEquals(2, accepted);
    assertEquals(0, document().path("progress").path("questions").size());
    assertEquals(0, repairCharges());

    int more = 0;
    for (int attempt = 0; attempt < 20; attempt++) {
      if (recovery.nestedToolRetry(RUN, new ConnectException("connection reset"), "ceil-" + attempt, 100)) {
        more++;
      }
    }
    assertEquals(10, more);
    assertEquals(12, technicalCharges());
  }

  @Test
  void revealedEarlierRecordRoutesBackwardWhenAnotherTaskIsPending() throws Exception {
    String runId = "run-earlier-owner";
    seed(runId, withTaskState("LOGICAL_FLOW", "PENDING"));
    WorkRecovery.Defect defect =
        new WorkRecovery.Defect(
            "",
            "rule-priority",
            "WRONG_OPERATION",
            "behavior",
            "Priority targets the wrong operation.",
            List.of("src-om"),
            "create",
            "binding");

    WorkRecovery.Result routed = recovery.route(runId, defect, "cmd-earlier");
    WorkRecovery.Result replay = recovery.route(runId, defect, "cmd-earlier");

    String cause = WorkRecovery.causeKey(runId, "rule-priority", "WRONG_OPERATION", "behavior");
    assertEquals(cause, routed.causeKey());
    assertEquals(cause, replay.causeKey());
    assertEquals(WorkStage.SERVICES, routed.owner());
    assertEquals(WorkStage.SERVICES, replay.owner());
    assertTrue(routed.dispatched());
    assertEquals(2, replay.repairsRemaining());
    assertEquals("PENDING", taskState(runId, "LOGICAL_FLOW"));
    assertEquals("PENDING", taskState(runId, "SERVICES"));
    assertEquals("NEEDS_RECHECK", taskState(runId, "DATA_BEHAVIOR"));
    assertEquals(1, repairCharges(runId));
  }

  @Test
  void laterPendingTaskDoesNotMoveTheCauseForward() throws Exception {
    String runId = "run-later-pending";
    seed(runId, withTaskState("DATA_BEHAVIOR", "PENDING"));
    WorkRecovery.Defect defect =
        new WorkRecovery.Defect(
            "",
            "req-flow",
            "WRONG_OPERATION",
            "text",
            "The call is wrong.",
            List.of("src-om"),
            "create",
            "binding");

    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class, () -> recovery.route(runId, defect, "cmd-later"));

    assertEquals("NOT_EARLIER_OWNER", rejected.code());
    assertEquals("ACCEPTED", taskState(runId, "LOGICAL_FLOW"));
    assertEquals("ACCEPTED", taskState(runId, "SERVICES"));
    assertEquals("PENDING", taskState(runId, "DATA_BEHAVIOR"));
    assertEquals(0, document(runId).path("progress").path("findings").size());
    assertEquals(0, repairCharges(runId));
  }

  @Test
  void stageBetweenTheOriginAndTheCurrentOwnerDoesNotMoveTheCauseForward() {
    WorkRecovery.Result logical =
        recovery.route(
            RUN,
            new WorkRecovery.Defect(
                "",
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "Priority targets the wrong operation.",
                List.of("src-om"),
                "req-flow",
                "text"),
            "cmd-flow");

    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                recovery.route(
                    RUN,
                    new WorkRecovery.Defect(
                        logical.findingId(),
                        "rule-priority",
                        "WRONG_OPERATION",
                        "behavior",
                        "The selected operation is not createTask.",
                        List.of("src-om"),
                        "create",
                        "binding"),
                    "cmd-services"));

    assertEquals("NOT_EARLIER_OWNER", rejected.code());
    assertEquals(WorkStage.LOGICAL_FLOW, logical.owner());
    assertEquals(
        WorkRecovery.causeKey(RUN, "rule-priority", "WRONG_OPERATION", "behavior"),
        logical.causeKey());
    assertEquals(logical.causeKey(), recovery.causeKey(RUN, logical.findingId()));
    assertEquals("LOGICAL_FLOW", runs.load(RUN).orElseThrow().run().currentStageId());
    assertEquals("PENDING", taskState("LOGICAL_FLOW"));
    assertEquals("NEEDS_RECHECK", taskState("SERVICES"));
    assertEquals("NEEDS_RECHECK", taskState("DATA_BEHAVIOR"));
    assertEquals(1, repairCharges());
  }

  @Test
  void blockedFourthInvocationDoesNotChangeTheOwner() {
    String findingId = "";
    for (int attempt = 1; attempt <= 3; attempt++) {
      WorkRecovery.Result routed =
          recovery.route(
              RUN,
              new WorkRecovery.Defect(
                  findingId,
                  "rule-priority",
                  "WRONG_OPERATION",
                  "behavior",
                  "Wording " + attempt,
                  List.of("src-om"),
                  "",
                  ""),
              "cmd-owner-" + attempt);
      findingId = routed.findingId();
      assertEquals(WorkStage.DATA_BEHAVIOR, routed.owner());
    }

    WorkRecovery.Result blocked =
        recovery.route(
            RUN,
            new WorkRecovery.Defect(
                findingId,
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "Wording 4",
                List.of("src-om"),
                "req-flow",
                "text"),
            "cmd-owner-4");

    assertFalse(blocked.dispatched());
    assertTrue(blocked.exhausted());
    assertEquals(WorkStage.DATA_BEHAVIOR, blocked.owner());
    assertEquals("PENDING", taskState("DATA_BEHAVIOR"));
    assertEquals("ACCEPTED", taskState("LOGICAL_FLOW"));
    assertEquals("ACCEPTED", taskState("SERVICES"));
    assertEquals(3, repairCharges());
  }

  @Test
  void suppliedEvidenceIdOpensACauseWhenTheSourceIsNotTheFixture() throws Exception {
    String runId = "run-other-source";
    seed(runId, DOCUMENT.replace("src-om", "src-order"));
    WorkRecovery.Result routed =
        recovery.route(
            runId,
            WorkRecovery.Defect.of(
                "req-flow",
                "MISSING_FACT",
                "text",
                "Which order id should be kept?",
                "src-order"),
            "cmd-source");

    assertTrue(routed.dispatched());
    assertEquals(
        WorkRecovery.causeKey(runId, "req-flow", "MISSING_FACT", "text"), routed.causeKey());
    assertEquals(WorkStage.LOGICAL_FLOW, routed.owner());
    assertEquals(List.of("src-order"), findingEvidence(runId, routed.findingId()));
  }

  private void seed(String runId, String documentJson) throws Exception {
    runs.create(
        new RunSnapshot(
            runId,
            "conversation-" + runId,
            1L,
            RunStatus.RUNNING,
            "DATA_BEHAVIOR",
            List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        runId,
        new WorkDocumentState("pending", JSON.readValue(documentJson, ChainWorkDocument.class)),
        "cmd-seed-" + runId,
        new WorkRepairBudget(3));
  }

  private static String withTaskState(String stage, String state) {
    String current = "\"state\":\"ACCEPTED\",\"stage\":\"" + stage + "\"";
    String updated = "\"state\":\"" + state + "\",\"stage\":\"" + stage + "\"";
    return DOCUMENT.replace(current, updated);
  }

  private JsonNode document() {
    return document(RUN);
  }

  private JsonNode document(String runId) {
    return JSON.valueToTree(documents.read(runId).document());
  }

  private String taskState(String stage) {
    return taskState(RUN, stage);
  }

  private String taskState(String runId, String stage) {
    for (JsonNode task : document(runId).path("progress").path("tasks")) {
      if (stage.equals(task.path("stage").asText())) {
        return task.path("state").asText();
      }
    }
    return "";
  }

  private List<String> recheckStages() {
    List<String> stages = new ArrayList<>();
    for (JsonNode stage : document().path("progress").path("recheckStages")) {
      stages.add(stage.asText());
    }
    return stages;
  }

  private List<String> ruleIds() {
    List<String> ids = new ArrayList<>();
    for (JsonNode step : document().path("flow").path("steps")) {
      if (!"create".equals(step.path("id").asText())) {
        continue;
      }
      for (JsonNode rule : step.path("data").path("transfers").get(0).path("rules")) {
        ids.add(rule.path("id").asText());
      }
    }
    return ids;
  }

  private String findingCategory(String findingId) {
    for (JsonNode finding : document().path("progress").path("findings")) {
      if (findingId.equals(finding.path("id").asText())) {
        return finding.path("issueCategory").asText();
      }
    }
    return "";
  }

  private List<String> findingEvidence(String runId, String findingId) {
    List<String> evidenceIds = new ArrayList<>();
    for (JsonNode finding : document(runId).path("progress").path("findings")) {
      if (!findingId.equals(finding.path("id").asText())) {
        continue;
      }
      for (JsonNode evidenceId : finding.path("evidenceIds")) {
        evidenceIds.add(evidenceId.asText());
      }
    }
    return evidenceIds;
  }

  private String nextAction() {
    JsonNode questions = document().path("progress").path("questions");
    return questions.isEmpty() ? "" : questions.get(0).path("question").asText();
  }

  private long repairCharges() {
    return repairCharges(RUN);
  }

  private long repairCharges(String runId) {
    return runs.load(runId).orElseThrow().transitions().stream()
        .filter(
            transition ->
                transition.reason() != null
                    && transition.reason()
                        .startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX))
        .count();
  }

  private long technicalCharges() {
    return runs.load(RUN).orElseThrow().transitions().stream()
        .filter(
            transition ->
                transition.reason() != null
                    && transition.reason().startsWith(RecoveryAttemptLedger.TECHNICAL_RETRY_REASON_PREFIX))
        .count();
  }

  private static final String DOCUMENT =
      """
      {
        "schemaVersion": 2,
        "documentId": "doc-recovery",
        "sources": [{
          "id": "src-om",
          "role": "MAPPING",
          "contentReference": "artifact://mapping",
          "contentHash": "hash-map",
          "originalName": "mapping.txt",
          "suppliedIdentifier": "MAP-1",
          "correctionOf": []
        }],
        "requirements": [
          {"id":"req-flow","text":"Create a task","sourceIds":["src-om"],"supersededRequirementId":""},
          {"id":"req-other","text":"Keep the order id","sourceIds":["src-om"],"supersededRequirementId":""}
        ],
        "flow": {
          "steps": [
            {"id":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the order","sourceIds":["src-om"],"requirementIds":["req-flow"],"binding":null,"data":{"transfers":[],"retainedValues":[]}},
            {"id":"create","kind":"SERVICE_CALL","label":"Task","intent":"Create the task","sourceIds":["src-om"],"requirementIds":["req-flow"],"binding":{"catalogId":"sys-wfm","version":"2024.4","operationId":"createTask","protocol":"http","method":"POST","path":"/wfm/v1/tasks","contractReferences":["spec-create"],"exposedPorts":["request"]},"data":{"transfers":[{
              "id":"xfer-request",
              "sourcePorts":[{"stepId":"start","portName":"payload"}],
              "targetPort":{"stepId":"create","portName":"request"},
              "requirementIds":["req-flow"],
              "rules":[
                {"id":"rule-subject","sources":[],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Subject","retainedValueId":""},"constants":[],"behavior":"name","evidenceIds":["src-om"]},
                {"id":"rule-priority","sources":[],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Priority","retainedValueId":""},"constants":[],"behavior":"high to High","evidenceIds":["src-om"]}
              ],
              "decision":"UNSPECIFIED"
            }],"retainedValues":[]}}
          ],
          "connections": [],
          "sequenceGroups": [{"id":"group-main","memberStepIds":["start","create"]}],
          "conditionGroups": [],
          "splitGroups": [],
          "loopGroups": [],
          "retryGroups": [],
          "errorScopeGroups": []
        },
        "progress": {
          "tasks": [
            {"taskId":"logical-design","state":"ACCEPTED","stage":"LOGICAL_FLOW","skillId":"logical-design"},
            {"taskId":"operation-selection","state":"ACCEPTED","stage":"SERVICES","skillId":"operation-selection"},
            {"taskId":"mapping-initial","state":"ACCEPTED","stage":"DATA_BEHAVIOR","skillId":"data-mapping"}
          ],
          "findings": [],
          "questions": [],
          "approvalReference": "",
          "derivedResultReferences": [],
          "recheckStages": []
        }
      }
      """;
}
