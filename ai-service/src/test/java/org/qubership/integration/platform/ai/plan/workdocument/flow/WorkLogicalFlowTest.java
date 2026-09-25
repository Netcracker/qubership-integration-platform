package org.qubership.integration.platform.ai.plan.workdocument.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkLogicalFlowTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-flow-1";
  private static final String SOURCE_ID = "src-om";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private static final String LISTS =
      """
      "requirements":[],"steps":[],"connections":[],"sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"transfers":[],"rules":[],"retainedValues":[],"deletes":[]
      """;

  private ProductPipelineRunStore runs;
  private WorkDocumentService documents;
  private WorkLogicalFlow flow;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    runs = new ProductPipelineRunStore(blobs, JSON, clock);
    documents = new WorkDocumentService(runs, artifacts, JSON);
    runs.create(openRun());
    ChainWorkDocument body = JSON.readValue(emptyDocument(), ChainWorkDocument.class);
    documents.intake(RUN_ID, new WorkDocumentState("pending", body), "cmd-intake", new WorkRepairBudget(3));
    flow = new WorkLogicalFlow(documents, new WorkTaskExecutor(documents, runs, clock));
  }

  @Test
  void omExampleStoresTriggerCallAndReplyAndKeepsCompleteTaskAsRequirement() throws Exception {
    String skill = Files.readString(skillPath());
    List<String> prompts = new ArrayList<>();
    WorkCommit commit =
        flow.design(
            RUN_ID,
            materials(),
            prompt -> {
              prompts.add(prompt);
              return omCapture();
            });

    assertTrue(prompts.get(0).contains("Do not add a step that only receives the synchronous result"));
    assertTrue(prompts.get(0).contains("Do not merge steps that share an operation, label, or service name"));
    assertTrue(skill.contains("Do not add a step that only receives the synchronous result"));
    JsonNode steps = steps(commit.state());
    assertEquals(List.of("TRIGGER", "SERVICE_CALL", "REPLY"), kinds(steps));
    assertEquals(List.of("onTaskStart", "createTask", "onTaskResult"), labels(steps));
    assertFalse(labels(steps).contains("completeTask"));
    JsonNode requirements = JSON.valueToTree(commit.state().document()).path("requirements");
    assertEquals(1, requirements.size());
    assertTrue(requirements.get(0).path("text").asText().contains("completeTask"));
    assertEquals(SOURCE_ID, steps.get(0).path("sourceIds").get(0).asText());
    assertEquals(List.of("success", "failure"), outcomesFrom(commit.state(), stepId(steps, "createTask")));
  }

  @Test
  void synchronousResultDoesNotBecomeAnotherInteraction() {
    Reference before = runs.load(RUN_ID).orElseThrow().run().workDocumentRef();

    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () -> flow.design(RUN_ID, materials(), prompt -> synchronousResultCapture()));

    assertEquals("SYNCHRONOUS_RESULT", rejected.code());
    assertEquals(before, runs.load(RUN_ID).orElseThrow().run().workDocumentRef());
    assertEquals(0, steps(documents.read(RUN_ID)).size());
  }

  @Test
  void localProcessingOnSuccessStaysAndReceiveResultIsRejected() {
    WorkCommit kept = flow.design(RUN_ID, materials(), prompt -> localProcessingCapture());

    JsonNode steps = steps(kept.state());
    assertEquals("LOCAL", stepById(steps, stepId(steps, "Normalize")).path("kind").asText());
    assertEquals(List.of("success", "failure"), outcomesFrom(kept.state(), stepId(steps, "createTask")));
    Reference afterLocal = runs.load(RUN_ID).orElseThrow().run().workDocumentRef();

    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () -> flow.design(RUN_ID, materials(), prompt -> synchronousResultCapture()));

    assertEquals("SYNCHRONOUS_RESULT", rejected.code());
    assertEquals(afterLocal, runs.load(RUN_ID).orElseThrow().run().workDocumentRef());
    assertEquals("Normalize", stepById(steps(documents.read(RUN_ID)), stepId(steps, "Normalize")).path("label").asText());
  }

  @Test
  void callbackAndRepeatedCallsStayDistinct() {
    WorkCommit commit = flow.design(RUN_ID, materials(), prompt -> callbackAndRepeatCapture());

    JsonNode steps = steps(commit.state());
    List<String> calls = new ArrayList<>();
    String callback = "";
    for (JsonNode step : steps) {
      if ("SERVICE_CALL".equals(step.path("kind").asText()) && "createTask".equals(step.path("label").asText())) {
        calls.add(step.path("id").asText());
      }
      if ("callback".equals(step.path("label").asText())) {
        callback = step.path("id").asText();
      }
    }
    assertEquals(2, calls.size());
    assertFalse(calls.get(0).equals(calls.get(1)));
    assertFalse(callback.isBlank());
    assertTrue(outcomesTouching(commit.state(), callback).contains("correlation"));
    assertEquals(SOURCE_ID, stepById(steps, callback).path("sourceIds").get(0).asText());
  }

  @Test
  void repairKeepsUnaffectedStepIdsAndRetainedEvidence() throws Exception {
    String repairRun = "run-repair";
    runs.create(
        new RunSnapshot(
            repairRun,
            "conversation-repair",
            1L,
            RunStatus.RUNNING,
            "LOGICAL_FLOW",
            List.of(new StageSnapshot("LOGICAL_FLOW", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        repairRun,
        new WorkDocumentState("pending", JSON.readValue(seededDocument(), ChainWorkDocument.class)),
        "cmd-seed",
        new WorkRepairBudget(3));
    WorkCommit repaired =
        flow.repair(
            repairRun,
            "reply",
            materials(),
            prompt ->
                prepared(
                    """
                    "steps":[{"existingId":"reply","alias":"","kind":"REPLY","label":"onTaskResult","intent":"Return the corrected result","sourceRefs":["src-om"],"requirementRefs":["req-constant"]}]
                    """));

    JsonNode steps = steps(repaired.state());
    assertEquals("trigger", stepId(steps, "onTaskStart"));
    assertEquals("create", stepId(steps, "createTask"));
    assertEquals("Return the corrected result", stepById(steps, "reply").path("intent").asText());
    JsonNode retained =
        stepById(steps, "create").path("data").path("retainedValues").get(0);
    assertEquals("keep-exec", retained.path("id").asText());
    assertEquals(SOURCE_ID, retained.path("evidenceIds").get(0).asText());
    assertEquals(
        "gate-group",
        WorkLogicalFlow.planningTopology(repaired.state()).path("conditionGroups").get(0).path("id").asText());
  }

  @Test
  void conditionAndLoopTopologyReachesPlanning() {
    WorkCommit commit = flow.design(RUN_ID, materials(), prompt -> topologyCapture());

    JsonNode stored = steps(commit.state());
    JsonNode planning = WorkLogicalFlow.planningTopology(commit.state());
    assertEquals(stepId(stored, "Priority gate"), planning.path("conditionGroups").get(0).path("ownerStepId").asText());
    assertEquals(stepId(stored, "High path"), planning.path("conditionGroups").get(0).path("branches").get(0).path("entryStepId").asText());
    assertEquals(stepId(stored, "Each item"), planning.path("loopGroups").get(0).path("ownerStepId").asText());
    assertEquals(stepId(stored, "createTask"), planning.path("loopGroups").get(0).path("bodyEntryStepId").asText());
    assertEquals("COPY", planning.path("loopGroups").get(0).path("loopMode").asText());
  }

  @Test
  void contradictionOpensLogicalRepair() {
    WorkCommit designed = flow.design(RUN_ID, materials(), prompt -> omCapture());
    String callId = stepId(steps(designed.state()), "createTask");
    WorkCommit commit =
        flow.design(
            RUN_ID,
            materials(),
            prompt ->
                outcome(
                    "INPUT_DEFECT",
                    "",
                    "",
                    callId,
                    "The call contradicts the source.",
                    "CONTRADICTION"));

    JsonNode finding = JSON.valueToTree(commit.state().document()).path("progress").path("findings").get(0);
    assertEquals(callId, finding.path("recordRef").asText());
    WorkTaskScope repair =
        RequirementDiscoveryCapability.logicalRepairScope(commit.state(), finding.path("recordRef").asText());
    assertEquals(WorkStage.LOGICAL_FLOW, repair.stage());
    assertEquals(WorkLogicalFlow.SKILL_ID, repair.skillId());
    assertEquals(List.of(callId), repair.ownedRecordIds());
    assertTrue(repair.replacePermitted());
    assertFalse(repair.createPermitted());
  }

  private static WorkTaskMaterials materials() {
    return new WorkTaskMaterials(
        List.of(),
        List.of("runtime-catalog-only"),
        Map.of(SOURCE_ID, "Triggered on onTaskStart, call createTask. commandType completeTask is a payload constant."));
  }

  private static String omCapture() {
    return prepared(
        """
        "requirements":[{"existingId":"","alias":"constant","text":"commandType is the constant completeTask","sourceRefs":["src-om"],"supersededRef":""}],
        "steps":[
          {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["src-om"],"requirementRefs":["constant"]},
          {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceRefs":["src-om"],"requirementRefs":["constant"]},
          {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["src-om"],"requirementRefs":["constant"]}
        ],
        "connections":[
          {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Then create the task","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"ok","sourceStepRef":"create","outcome":"success","targetStepRef":"result","routingIntent":"Return the synchronous success","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"bad","sourceStepRef":"create","outcome":"failure","targetStepRef":"result","routingIntent":"Return the synchronous failure","evidenceRefs":["src-om"]}
        ]
        """);
  }

  private static String localProcessingCapture() {
    return prepared(
        """
        "steps":[
          {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"normalize","kind":"LOCAL","label":"Normalize","intent":"Normalize the successful payload","sourceRefs":[],"requirementRefs":[]},
          {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["src-om"],"requirementRefs":[]}
        ],
        "connections":[
          {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Then create the task","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"ok","sourceStepRef":"create","outcome":"success","targetStepRef":"normalize","routingIntent":"Then normalize","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"onward","sourceStepRef":"normalize","outcome":"success","targetStepRef":"result","routingIntent":"Then reply","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"bad","sourceStepRef":"create","outcome":"failure","targetStepRef":"result","routingIntent":"Return the synchronous failure","evidenceRefs":["src-om"]}
        ]
        """);
  }

  private static String synchronousResultCapture() {
    return prepared(
        """
        "steps":[
          {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"received","kind":"TRIGGER","label":"Salesforce result","intent":"Receive the synchronous result","sourceRefs":[],"requirementRefs":[]},
          {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["src-om"],"requirementRefs":[]}
        ],
        "connections":[
          {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Then create the task","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"ok","sourceStepRef":"create","outcome":"success","targetStepRef":"received","routingIntent":"Receive the result","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"bad","sourceStepRef":"create","outcome":"failure","targetStepRef":"received","routingIntent":"Receive the failure","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"done","sourceStepRef":"received","outcome":"success","targetStepRef":"result","routingIntent":"Then reply","evidenceRefs":["src-om"]}
        ]
        """);
  }

  private static String callbackAndRepeatCapture() {
    return prepared(
        """
        "steps":[
          {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"first","kind":"SERVICE_CALL","label":"createTask","intent":"Create the first task","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"second","kind":"SERVICE_CALL","label":"createTask","intent":"Create the second task","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"back","kind":"TRIGGER","label":"callback","intent":"Accept the later callback","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["src-om"],"requirementRefs":[]}
        ],
        "connections":[
          {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"first","routingIntent":"First call","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"ok1","sourceStepRef":"first","outcome":"success","targetStepRef":"second","routingIntent":"Second call","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"bad1","sourceStepRef":"first","outcome":"failure","targetStepRef":"result","routingIntent":"First failure","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"ok2","sourceStepRef":"second","outcome":"success","targetStepRef":"result","routingIntent":"Second success","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"bad2","sourceStepRef":"second","outcome":"failure","targetStepRef":"result","routingIntent":"Second failure","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"corr","sourceStepRef":"second","outcome":"correlation","targetStepRef":"back","routingIntent":"Correlate the callback","evidenceRefs":["src-om"]}
        ]
        """);
  }

  private static String topologyCapture() {
    return prepared(
        """
        "steps":[
          {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"gate","kind":"LOCAL","label":"Priority gate","intent":"Branch on priority","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"high","kind":"LOCAL","label":"High path","intent":"Handle high priority","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"items","kind":"LOCAL","label":"Each item","intent":"Repeat for each item","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"body","kind":"SERVICE_CALL","label":"createTask","intent":"Create one task","sourceRefs":["src-om"],"requirementRefs":[]},
          {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["src-om"],"requirementRefs":[]}
        ],
        "connections":[
          {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"gate","routingIntent":"Then branch","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"ok","sourceStepRef":"body","outcome":"success","targetStepRef":"result","routingIntent":"Return success","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"bad","sourceStepRef":"body","outcome":"failure","targetStepRef":"result","routingIntent":"Return failure","evidenceRefs":["src-om"]}
        ],
        "conditionGroups":[{
          "existingId":"","alias":"priority","ownerStepRef":"gate","reconvergenceStepRef":"items",
          "branches":[
            {"existingId":"","alias":"when-high","role":"IF","predicate":"$.priority = high","priority":1,"entryStepRef":"high","exitStepRefs":["high"]},
            {"existingId":"","alias":"otherwise","role":"ELSE","predicate":"","priority":2,"entryStepRef":"items","exitStepRefs":["items"]}
          ]
        }],
        "loopGroups":[{
          "existingId":"","alias":"each","ownerStepRef":"items","bodyEntryStepRef":"body","bodyExitStepRefs":["body"],
          "exitStepRef":"result","loopMode":"COPY","loopExpression":"$.items","loopSafetyBound":20
        }]
        """);
  }

  private static String prepared(String body) {
    String merged = mergeLists(body);
    return "{\"outcome\":\"PREPARED\"," + merged + ",\"question\":\"\",\"unresolvedChoice\":\"\",\"clarificationEvidenceIds\":[],\"defectRecordRef\":\"\",\"contradiction\":\"\",\"defectEvidenceIds\":[],\"issueCategory\":\"\"}";
  }

  private static String outcome(
      String outcome, String question, String choice, String record, String contradiction, String category) {
    return "{"
        + "\"outcome\":\""
        + outcome
        + "\","
        + LISTS
        + ",\"question\":\""
        + question
        + "\",\"unresolvedChoice\":\""
        + choice
        + "\",\"clarificationEvidenceIds\":[\""
        + SOURCE_ID
        + "\"],\"defectRecordRef\":\""
        + record
        + "\",\"contradiction\":\""
        + contradiction
        + "\",\"defectEvidenceIds\":[\""
        + SOURCE_ID
        + "\"],\"issueCategory\":\""
        + category
        + "\"}";
  }

  private static String mergeLists(String body) {
    String lists = LISTS;
    for (String name :
        List.of(
            "requirements",
            "steps",
            "connections",
            "sequenceGroups",
            "conditionGroups",
            "splitGroups",
            "loopGroups",
            "retryGroups",
            "errorScopeGroups")) {
      String key = "\"" + name + "\":";
      int at = body.indexOf(key);
      if (at < 0) {
        continue;
      }
      lists = lists.replace(key + "[],", "");
      lists = lists.replace(key + "[]", "");
    }
    if (lists.endsWith(",")) {
      lists = lists.substring(0, lists.length() - 1);
    }
    if (lists.isBlank()) {
      return body;
    }
    return lists + "," + body;
  }

  private static String seededDocument() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-repair",
          "sources": [{
            "id": "src-om",
            "role": "request",
            "contentReference": "artifact://om",
            "contentHash": "hash-om",
            "originalName": "request.md",
            "suppliedIdentifier": "OM-1",
            "correctionOf": []
          }],
          "requirements": [{
            "id": "req-constant",
            "text": "commandType is the constant completeTask",
            "sourceIds": ["src-om"],
            "supersededRequirementId": ""
          }],
          "flow": {
            "steps": [
              {"id":"trigger","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceIds":["src-om"],"requirementIds":["req-constant"],"binding":null,"data":{"transfers":[],"retainedValues":[]}},
              {"id":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceIds":["src-om"],"requirementIds":["req-constant"],"binding":null,"data":{"transfers":[],"retainedValues":[{"id":"keep-exec","source":{"kind":"STEP_PORT","stepId":"trigger","port":"INBOUND_PAYLOAD","fieldPath":"$.executionId","retainedValueId":""},"intendedUse":"Response echo","evidenceIds":["src-om"]}]}},
              {"id":"reply","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceIds":["src-om"],"requirementIds":["req-constant"],"binding":null,"data":{"transfers":[],"retainedValues":[]}},
              {"id":"gate","kind":"LOCAL","label":"Priority gate","intent":"Branch on priority","sourceIds":["src-om"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}}
            ],
            "connections": [
              {"id":"go","sourceStepId":"trigger","outcome":"success","targetStepId":"create","routingIntent":"Then create","evidenceIds":["src-om"]},
              {"id":"ok","sourceStepId":"create","outcome":"success","targetStepId":"reply","routingIntent":"Success","evidenceIds":["src-om"]},
              {"id":"bad","sourceStepId":"create","outcome":"failure","targetStepId":"reply","routingIntent":"Failure","evidenceIds":["src-om"]}
            ],
            "sequenceGroups": [],
            "conditionGroups": [{
              "id": "gate-group",
              "ownerStepId": "gate",
              "branches": [{"id":"when","role":"IF","predicate":"$.priority = high","priority":1,"entryStepId":"reply","exitStepIds":["reply"]}],
              "reconvergenceStepId": ""
            }],
            "splitGroups": [],
            "loopGroups": [],
            "retryGroups": [],
            "errorScopeGroups": []
          },
          "progress": {"tasks":[],"findings":[],"questions":[],"approvalReference":"","derivedResultReferences":[],"recheckStages":[]}
        }
        """;
  }

  private static String emptyDocument() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-flow",
          "sources": [{
            "id": "src-om",
            "role": "request",
            "contentReference": "artifact://om",
            "contentHash": "hash-om",
            "originalName": "request.md",
            "suppliedIdentifier": "OM-1",
            "correctionOf": []
          }],
          "requirements": [],
          "flow": {
            "steps": [],
            "connections": [],
            "sequenceGroups": [],
            "conditionGroups": [],
            "splitGroups": [],
            "loopGroups": [],
            "retryGroups": [],
            "errorScopeGroups": []
          },
          "progress": {
            "tasks": [],
            "findings": [],
            "questions": [],
            "approvalReference": "",
            "derivedResultReferences": [],
            "recheckStages": []
          }
        }
        """;
  }

  private static Path skillPath() {
    return Path.of("../integration-platform-skills/.apm/skills/logical-design/SKILL.md");
  }

  private static RunSnapshot openRun() {
    return new RunSnapshot(
        RUN_ID,
        "conversation-flow-1",
        1L,
        RunStatus.RUNNING,
        "LOGICAL_FLOW",
        List.of(new StageSnapshot("LOGICAL_FLOW", StageStatus.RUNNING, List.of(), null)),
        null);
  }

  private static JsonNode steps(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("flow").path("steps");
  }

  private static List<String> kinds(JsonNode steps) {
    List<String> kinds = new ArrayList<>();
    steps.forEach(step -> kinds.add(step.path("kind").asText()));
    return kinds;
  }

  private static List<String> labels(JsonNode steps) {
    List<String> labels = new ArrayList<>();
    steps.forEach(step -> labels.add(step.path("label").asText()));
    return labels;
  }

  private static String stepId(JsonNode steps, String label) {
    for (JsonNode step : steps) {
      if (label.equals(step.path("label").asText())) {
        return step.path("id").asText();
      }
    }
    return "";
  }

  private static JsonNode stepById(JsonNode steps, String id) {
    for (JsonNode step : steps) {
      if (id.equals(step.path("id").asText())) {
        return step;
      }
    }
    return JSON.nullNode();
  }

  private static List<String> outcomesFrom(WorkDocumentState state, String stepId) {
    List<String> outcomes = new ArrayList<>();
    for (JsonNode connection : JSON.valueToTree(state.document()).path("flow").path("connections")) {
      if (stepId.equals(connection.path("sourceStepId").asText())) {
        outcomes.add(connection.path("outcome").asText());
      }
    }
    return outcomes;
  }

  private static List<String> outcomesTouching(WorkDocumentState state, String stepId) {
    List<String> outcomes = new ArrayList<>();
    for (JsonNode connection : JSON.valueToTree(state.document()).path("flow").path("connections")) {
      if (stepId.equals(connection.path("sourceStepId").asText())
          || stepId.equals(connection.path("targetStepId").asText())) {
        outcomes.add(connection.path("outcome").asText());
      }
    }
    return outcomes;
  }
}
