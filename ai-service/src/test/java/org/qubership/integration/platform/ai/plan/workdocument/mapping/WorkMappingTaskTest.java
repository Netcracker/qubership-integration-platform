package org.qubership.integration.platform.ai.plan.workdocument.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.service.output.OutputParsingException;
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
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingCondition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingConstant;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingFieldRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;

class WorkMappingTaskTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-map-1";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private static final String LISTS =
      """
      "requirements":[],"steps":[],"connections":[],"sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"deletes":[]
      """;

  private WorkDocumentService documents;
  private WorkMapping mapping;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, JSON, clock);
    documents = new WorkDocumentService(runs, artifacts, JSON);
    runs.create(
        new RunSnapshot(
            RUN_ID,
            "conversation-map",
            1L,
            RunStatus.RUNNING,
            "DATA_BEHAVIOR",
            List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        RUN_ID,
        new WorkDocumentState("pending", JSON.readValue(seededDocument(), ChainWorkDocument.class)),
        "cmd-seed",
        new WorkRepairBudget(3));
    mapping = new WorkMapping(documents, new WorkTaskExecutor(documents, runs, clock));
  }

  @Test
  void suppliedMappingStaysLinkedWithoutCopyingRetainedFieldsOntoTheRequest() throws Exception {
    WorkCommit commit = mapping.interpret(RUN_ID, materials(true), prompt -> suppliedCapture(true));

    assertEquals("PREPARED", commit.outcome().name());
    JsonNode document = JSON.valueToTree(commit.state().document());
    JsonNode call = step(document, "create");
    assertEquals(1, serviceCalls(document));
    List<JsonNode> rules = rules(document);
    assertTrue(rules.size() >= 5);
    for (JsonNode rule : rules) {
      assertTrue(rule.path("evidenceIds").toString().contains("src-map"));
    }
    assertTrue(behaviors(rules).contains("SALESFORCE_TASK_CREATE_ERROR"));
    assertFalse(requestTargets(call).contains("executionId"));
    assertFalse(requestTargets(call).contains("orderId"));
    assertFalse(requestTargets(call).contains("processInstanceId"));
    assertFalse(requestTargets(call).contains("executionNumber"));
    assertFalse(requestTargets(call).contains("taskId"));
    assertTrue(retainedLeaves(document).contains("processInstanceId"));
    JsonNode description = ruleTargeting(rules, "$.Description");
    assertEquals("$.Description", description.path("target").path("fieldPath").asText());
    assertEquals("string", description.path("behavior").asText().contains("string") ? "string" : schemaType("Description"));
  }

  @Test
  void processIdRenameAsksUnlessContextNamesBothFields() {
    WorkCommit asked = mapping.interpret(RUN_ID, materials(false), prompt -> processIdCapture());

    assertEquals("NEEDS_CLARIFICATION", asked.outcome().name());
    String questions = JSON.valueToTree(asked.state().document()).path("progress").path("questions").toString();
    assertTrue(questions.contains("processId"));
    assertTrue(questions.contains("processInstanceId"));
    assertFalse(targets(JSON.valueToTree(asked.state().document())).contains("$.processId"));

    WorkCommit accepted = mapping.interpret(RUN_ID, materials(true), prompt -> processIdCapture());
    assertEquals("PREPARED", accepted.outcome().name());
    assertTrue(targets(JSON.valueToTree(accepted.state().document())).contains("$.processId"));
  }

  @Test
  void contractNameIsNotAJsonPrefixAndUnknownPathsAreQuestions() {
    WorkCommit prefixed = mapping.interpret(RUN_ID, materials(false), prompt -> pathCapture("$.Task.Description", "OUTBOUND_REQUEST", "create"));
    assertEquals("NEEDS_CLARIFICATION", prefixed.outcome().name());
    assertFalse(targets(JSON.valueToTree(prefixed.state().document())).contains("$.Task.Description"));

    WorkCommit unknown = mapping.interpret(RUN_ID, materials(false), prompt -> pathCapture("$.id", "SUCCESS_RESPONSE", "create"));
    assertEquals("NEEDS_CLARIFICATION", unknown.outcome().name());
    assertFalse(targets(JSON.valueToTree(unknown.state().document())).contains("$.id"));
    assertEquals(1, serviceCalls(JSON.valueToTree(unknown.state().document())));

    WorkCommit unknownSource =
        mapping.interpret(
            RUN_ID,
            materials(false),
            prompt -> sourceCapture("$.id", "SUCCESS_RESPONSE", "create", "$.status"));
    assertEquals("NEEDS_CLARIFICATION", unknownSource.outcome().name());
    assertFalse(sourcePaths(JSON.valueToTree(unknownSource.state().document())).contains("$.id"));

    WorkCommit unknownError =
        mapping.interpret(
            RUN_ID,
            materials(false),
            prompt -> sourceCapture("$.error.text", "FAILURE_OUTCOME", "create", "$.status"));
    assertEquals("NEEDS_CLARIFICATION", unknownError.outcome().name());
    assertFalse(sourcePaths(JSON.valueToTree(unknownError.state().document())).contains("$.error.text"));
  }

  @Test
  void dollarSourcePathAsksAndDoesNotStoreTheRule() {
    WorkCommit asked =
        mapping.interpret(
            RUN_ID, materials(false), prompt -> sourceCapture("$", "INBOUND_PAYLOAD", "start", "$.name"));

    assertEquals("NEEDS_CLARIFICATION", asked.outcome().name());
    JsonNode document = JSON.valueToTree(asked.state().document());
    assertFalse(sourcePaths(document).contains("$"));
    assertEquals(0, rules(document).size());
    assertTrue(
        document
            .path("progress")
            .path("questions")
            .toString()
            .contains("Field path $ is not in the selected contract"));
  }

  @Test
  void initialPromptContainsTheSuppliedSource() {
    List<String> prompts = new ArrayList<>();
    mapping.interpret(
        RUN_ID,
        materials(false),
        prompt -> {
          prompts.add(prompt);
          return suppliedCapture(false);
        });

    assertFalse(prompts.isEmpty());
    assertTrue(prompts.getFirst().contains("supplied mapping"));
    assertTrue(prompts.getFirst().contains("Do not invent a catalog field."));
  }

  @Test
  void initialPromptContainsAttachedSchemaFieldAndStep() {
    List<String> prompts = new ArrayList<>();
    mapping.interpret(
        RUN_ID,
        materials(false),
        prompt -> {
          prompts.add(prompt);
          return suppliedCapture(false);
        });

    assertFalse(prompts.isEmpty());
    String prompt = prompts.getFirst();
    int field = prompt.indexOf("orderCreationDate");
    assertTrue(field >= 0);
    int lineStart = prompt.lastIndexOf('\n', field);
    int lineEnd = prompt.indexOf('\n', field);
    String schemaLine =
        prompt.substring(lineStart < 0 ? 0 : lineStart + 1, lineEnd < 0 ? prompt.length() : lineEnd);
    assertTrue(schemaLine.contains("start"));
    assertTrue(prompt.contains("supplied mapping"));
  }

  @Test
  void promptKeepsClarificationRecordsEmptyAndKeepsDescribedFormats() {
    List<String> prompts = new ArrayList<>();
    mapping.interpret(
        RUN_ID,
        materials(false),
        prompt -> {
          prompts.add(prompt);
          return suppliedCapture(false);
        });

    assertFalse(prompts.isEmpty());
    String prompt = prompts.getFirst();
    assertTrue(prompt.contains("Every record list is empty."));
    assertTrue(prompt.contains("Do not ask for a format the source already describes."));
    assertTrue(prompt.contains("A schema label is not an evidence id."));
  }

  @Test
  void promptForbidsDollarPathsAndSameFieldEcho() {
    List<String> prompts = new ArrayList<>();
    mapping.interpret(
        RUN_ID,
        materials(false),
        prompt -> {
          prompts.add(prompt);
          return suppliedCapture(false);
        });

    assertFalse(prompts.isEmpty());
    String prompt = prompts.getFirst();
    assertTrue(prompt.contains("Do not use $, an empty path, or a path you invented."));
    assertTrue(prompt.contains("Echo a retained value onto the same field name."));
  }

  @Test
  void constraintThatNamesOnlyProcessInstanceIdAsks() {
    WorkTaskMaterials onlyLonger =
        new WorkTaskMaterials(
            materials(false).schemas(),
            List.of("processInstanceId"),
            Map.of("src-map", "supplied mapping"));

    WorkCommit asked = mapping.interpret(RUN_ID, onlyLonger, prompt -> processIdCapture());

    assertEquals("NEEDS_CLARIFICATION", asked.outcome().name());
    String questions = JSON.valueToTree(asked.state().document()).path("progress").path("questions").toString();
    assertTrue(questions.contains("processId"));
    assertTrue(questions.contains("processInstanceId"));
    assertFalse(targets(JSON.valueToTree(asked.state().document())).contains("$.processId"));

    WorkTaskMaterials embedded =
        new WorkTaskMaterials(
            materials(false).schemas(),
            List.of("seeprocessId processInstanceId"),
            Map.of("src-map", "supplied mapping"));
    WorkCommit glued = mapping.interpret(RUN_ID, embedded, prompt -> processIdCapture());
    assertEquals("NEEDS_CLARIFICATION", glued.outcome().name());
  }

  @Test
  void schemaConstraintContainingBothLeavesStillAsks() {
    List<SchemaFragment> schemas = new ArrayList<>();
    for (SchemaFragment fragment : materials(false).schemas()) {
      if ("result".equals(fragment.stepId()) && "request".equals(fragment.portName())) {
        schemas.add(
            schema(
                "result",
                "request",
                "{\"type\":\"object\",\"properties\":{\"commandType\":{\"type\":\"string\"},\"executionId\":{\"type\":\"string\"},\"orderId\":{\"type\":\"string\"},\"processId\":{\"type\":\"string\"},\"processInstanceId\":{\"type\":\"string\"},\"executionNumber\":{\"type\":\"string\"},\"taskId\":{\"type\":\"string\"},\"sourceAppName\":{\"type\":\"string\"},\"error\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}}}"));
      } else {
        schemas.add(fragment);
      }
    }
    WorkTaskMaterials bothLeaves =
        new WorkTaskMaterials(schemas, List.of(), Map.of("src-map", "supplied mapping"));

    WorkCommit asked = mapping.interpret(RUN_ID, bothLeaves, prompt -> processIdCapture());

    assertEquals("NEEDS_CLARIFICATION", asked.outcome().name());
    String questions = JSON.valueToTree(asked.state().document()).path("progress").path("questions").toString();
    assertTrue(questions.contains("processId"));
    assertTrue(questions.contains("processInstanceId"));
    assertFalse(targets(JSON.valueToTree(asked.state().document())).contains("$.processId"));
  }

  @Test
  void priorityRepairLeavesEveryOtherRuleAndIdUnchanged() throws Exception {
    mapping.interpret(RUN_ID, materials(true), prompt -> suppliedCapture(true));
    String before = JSON.writeValueAsString(documents.read(RUN_ID).document());
    JsonNode priorTree = JSON.readTree(before);
    String priorityId = ruleId(priorTree, "$.Priority");
    String transferId = transferIdOf(priorTree, priorityId);

    WorkCommit repaired =
        mapping.repair(
            RUN_ID, priorityId, materials(true), prompt -> repairCapture(priorityId, transferId));

    JsonNode after = JSON.valueToTree(repaired.state().document());
    JsonNode prior = JSON.readTree(before);
    assertEquals("urgent maps to High", behaviorOf(after, priorityId));
    for (JsonNode rule : rules(prior)) {
      String id = rule.path("id").asText();
      if (id.equals(priorityId)) {
        continue;
      }
      assertEquals(rule.path("behavior").asText(), behaviorOf(after, id));
      assertEquals(rule.path("target").path("fieldPath").asText(), targetOf(after, id));
    }
  }

  @Test
  void parsingFailureIsAFailedCapture() {
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                mapping.interpret(
                    RUN_ID,
                    materials(false),
                    prompt -> {
                      throw new OutputParsingException("Mapping capture could not be parsed.", null);
                    }));
    assertEquals("MALFORMED_CAPTURE", rejected.code());
    assertEquals(0, rules(JSON.valueToTree(documents.read(RUN_ID).document())).size());
  }

  @Test
  void descriptiveRulesRoundTripWithoutATypedOperation() throws Exception {
    MappingIntentRule rule =
        MappingIntentRule.descriptive(
            "subject",
            List.of("req-1"),
            "$.Subject",
            List.of(new MappingFieldRef("start", "INBOUND_PAYLOAD", "$.name", "")),
            List.of(new MappingConstant("fallback", TextNode.valueOf("{subRequestType} task"))),
            "Use name, otherwise the formatted fallback.");
    MappingIntent intent =
        new MappingIntent("map-1", "start", MappingPort.REQUEST, "create", MappingPort.REQUEST, List.of(rule));
    String json = JSON.writeValueAsString(intent);
    assertFalse(json.contains("\"operation\""));
    MappingIntent read = JSON.readValue(json, MappingIntent.class);
    assertEquals(rule.behavior(), read.rules().getFirst().behavior());
    assertTrue(read.rules().getFirst().value() == null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MappingIntentRule(
                "subject",
                List.of(),
                "$.Subject",
                new MappingValue.Copy(new MappingSource.Message("start", MappingPort.REQUEST, "$.name")),
                MappingCondition.always(),
                MappingRuleStatus.PROPOSED,
                List.of(new MappingFieldRef("start", "INBOUND_PAYLOAD", "$.name", "")),
                List.of(),
                "prose and a typed value"));
  }

  @Test
  void emptyRulesAskAndAnExplicitDecisionKeepsItsEvidence() {
    WorkCommit unknown =
        mapping.interpret(RUN_ID, materials(false), prompt -> transferCapture("", List.of()));

    assertEquals("NEEDS_CLARIFICATION", unknown.outcome().name());
    assertEquals(0, rules(JSON.valueToTree(unknown.state().document())).size());
    assertTrue(
        JSON.valueToTree(unknown.state().document()).path("progress").path("questions").toString()
            .contains("no-mapping"));

    WorkCommit decided =
        mapping.interpret(RUN_ID, materials(false), prompt -> transferCapture("NO_MAPPING", List.of("start")));

    assertEquals("PREPARED", decided.outcome().name());
    JsonNode transfer = transfers(JSON.valueToTree(decided.state().document())).getFirst();
    assertEquals("NO_MAPPING", transfer.path("decision").asText());
    assertEquals(0, transfer.path("rules").size());
    assertTrue(transfer.path("requirementIds").toString().contains("start"));
  }

  @Test
  void g2FixturesUseTheContractCaseIds() throws Exception {
    JsonNode root =
        JSON.readTree(
            Files.readString(
                Path.of("e2e/product-pipeline/fixtures/work-checkpoints/g2-cases.json")));
    List<String> ids = new ArrayList<>();
    for (JsonNode item : root.path("cases")) {
      assertEquals("G2", item.path("gate").asText());
      if ("mapping".equals(item.path("checkpoint").asText())) {
        ids.add(item.path("id").asText());
      }
    }
    assertEquals(List.of("om-mapping", "priority-repair"), ids);
    assertTrue(root.toString().contains("Original request, response, and retained-context rules survive"));
    assertTrue(root.toString().contains("Only the assigned Priority rule changes"));
  }

  private static WorkTaskMaterials materials(boolean renameEvidence) {
    List<SchemaFragment> schemas =
        List.of(
            schema("start", "payload", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"subRequestType\":{\"type\":\"string\"},\"orderId\":{\"type\":\"string\"},\"executionId\":{\"type\":\"string\"},\"processInstanceId\":{\"type\":\"string\"},\"executionNumber\":{\"type\":\"string\"},\"taskId\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\"},\"parameters\":{\"type\":\"object\",\"properties\":{\"orderCreationDate\":{\"type\":\"string\"}}}}}"),
            schema("create", "request", "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"string\"},\"Priority\":{\"type\":\"string\"},\"Status\":{\"type\":\"string\"},\"ActivityDate\":{\"type\":\"string\"},\"Description\":{\"type\":\"string\"}}}"),
            schema("create", "success", "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}"),
            schema("create", "failure", "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}"),
            schema("result", "request", "{\"type\":\"object\",\"properties\":{\"commandType\":{\"type\":\"string\"},\"executionId\":{\"type\":\"string\"},\"orderId\":{\"type\":\"string\"},\"processId\":{\"type\":\"string\"},\"executionNumber\":{\"type\":\"string\"},\"taskId\":{\"type\":\"string\"},\"sourceAppName\":{\"type\":\"string\"},\"error\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}}}"));
    List<String> constraints = new ArrayList<>();
    if (renameEvidence) {
      constraints.add("For the response, processId is the retained processInstanceId from onTaskStart.");
    }
    return new WorkTaskMaterials(schemas, constraints, Map.of("src-map", "supplied mapping"));
  }

  private static SchemaFragment schema(String stepId, String port, String body) {
    return new SchemaFragment("schema-" + stepId + "-" + port, stepId, port, "hash-" + port, "ref-" + port, body);
  }

  private static String suppliedCapture(boolean includeRename) {
    String process =
        includeRename
            ? """
            ,{"existingId":"","alias":"rule-process","transferRef":"xfer-response","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-process"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.processId","retainedValueId":""},"constants":[],"behavior":"processId reads retained processInstanceId","evidenceRefs":["src-map"]}
            """
            : "";
    return """
        {"outcome":"PREPARED",%s,"transfers":[
          {"existingId":"","alias":"xfer-request","targetStepRef":"create","sourcePorts":[{"stepId":"start","portName":"payload"}],"targetPort":{"stepId":"create","portName":"request"},"requirementRefs":[],"decision":""},
          {"existingId":"","alias":"xfer-response","targetStepRef":"result","sourcePorts":[{"stepId":"create","portName":"success"}],"targetPort":{"stepId":"result","portName":"request"},"requirementRefs":[],"decision":""}
        ],"rules":[
          {"existingId":"","alias":"rule-subject","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.name","retainedValueId":""},{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.subRequestType","retainedValueId":""},{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.orderId","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Subject","retainedValueId":""},"constants":[],"behavior":"name, or a formatted fallback","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"rule-priority","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.priority","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Priority","retainedValueId":""},"constants":[],"behavior":"high, urgent, or critical to High","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"rule-status","transferRef":"xfer-request","sources":[],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Status","retainedValueId":""},"constants":[{"name":"status","value":"Not Started"}],"behavior":"constant Not Started","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"rule-activity","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.parameters.orderCreationDate","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.ActivityDate","retainedValueId":""},"constants":[],"behavior":"order creation date, else today","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"rule-description","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.taskId","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Description","retainedValueId":""},"constants":[],"behavior":"serialized string","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"rule-command","transferRef":"xfer-response","sources":[],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.commandType","retainedValueId":""},"constants":[{"name":"commandType","value":"completeTask"}],"behavior":"constant completeTask","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"rule-failure","transferRef":"xfer-response","sources":[{"kind":"STEP_PORT","stepId":"create","port":"FAILURE_OUTCOME","fieldPath":"$.status","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.error.code","retainedValueId":""},"constants":[{"name":"code","value":"SALESFORCE_TASK_CREATE_ERROR"}],"behavior":"SALESFORCE_TASK_CREATE_ERROR plus the failure text","evidenceRefs":["src-map"]}
          %s
        ],"retainedValues":[
          {"existingId":"","alias":"keep-execution","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.executionId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"keep-order","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.orderId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"keep-process","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.processInstanceId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"keep-number","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.executionNumber","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-map"]},
          {"existingId":"","alias":"keep-task","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.taskId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-map"]}
        ]}
        """
        .formatted(LISTS, process);
  }

  private static String processIdCapture() {
    return """
        {"outcome":"PREPARED",%s,"transfers":[
          {"existingId":"","alias":"xfer-response","targetStepRef":"result","sourcePorts":[{"stepId":"create","portName":"success"}],"targetPort":{"stepId":"result","portName":"request"},"requirementRefs":[],"decision":""}
        ],"rules":[
          {"existingId":"","alias":"rule-process","transferRef":"xfer-response","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-process"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.processId","retainedValueId":""},"constants":[],"behavior":"response process id","evidenceRefs":["src-map"]}
        ],"retainedValues":[
          {"existingId":"","alias":"keep-process","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.processInstanceId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-map"]}
        ]}
        """
        .formatted(LISTS);
  }

  private static String sourceCapture(String sourcePath, String sourcePort, String stepId, String targetPath) {
    return """
        {"outcome":"PREPARED",%s,"transfers":[
          {"existingId":"","alias":"xfer","targetStepRef":"%s","sourcePorts":[{"stepId":"start","portName":"payload"}],"targetPort":{"stepId":"%s","portName":"request"},"requirementRefs":[],"decision":""}
        ],"rules":[
          {"existingId":"","alias":"rule-path","transferRef":"xfer","sources":[{"kind":"STEP_PORT","stepId":"%s","port":"%s","fieldPath":"%s","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"%s","port":"%s","fieldPath":"%s","retainedValueId":""},"constants":[],"behavior":"read field","evidenceRefs":["src-map"]}
        ],"retainedValues":[]}
        """
        .formatted(LISTS, stepId, stepId, stepId, sourcePort, sourcePath, stepId, sourcePort, targetPath);
  }

  private static String pathCapture(String path, String port, String stepId) {
    return """
        {"outcome":"PREPARED",%s,"transfers":[
          {"existingId":"","alias":"xfer","targetStepRef":"%s","sourcePorts":[{"stepId":"start","portName":"payload"}],"targetPort":{"stepId":"%s","portName":"request"},"requirementRefs":[],"decision":""}
        ],"rules":[
          {"existingId":"","alias":"rule-path","transferRef":"xfer","sources":[],"target":{"kind":"STEP_PORT","stepId":"%s","port":"%s","fieldPath":"%s","retainedValueId":""},"constants":[],"behavior":"written field","evidenceRefs":["src-map"]}
        ],"retainedValues":[]}
        """
        .formatted(LISTS, stepId, stepId, stepId, port, path);
  }

  private static String transferCapture(String decision, List<String> requirementRefs) {
    String refs =
        requirementRefs.isEmpty()
            ? ""
            : "\"" + String.join("\",\"", requirementRefs) + "\"";
    return """
        {"outcome":"PREPARED",%s,"transfers":[
          {"existingId":"","alias":"xfer","targetStepRef":"create","sourcePorts":[{"stepId":"start","portName":"payload"}],"targetPort":{"stepId":"create","portName":"request"},"requirementRefs":[%s],"decision":"%s"}
        ],"rules":[],"retainedValues":[]}
        """
        .formatted(LISTS, refs, decision);
  }

  private static List<JsonNode> transfers(JsonNode document) {
    List<JsonNode> found = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        found.add(transfer);
      }
    }
    return found;
  }

  private static String repairCapture(String priorityId, String transferId) {
    return """
        {"outcome":"PREPARED",%s,"transfers":[],"rules":[
          {"existingId":"%s","alias":"","transferRef":"%s","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.priority","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Priority","retainedValueId":""},"constants":[],"behavior":"urgent maps to High","evidenceRefs":["src-map"]}
        ],"retainedValues":[]}
        """
        .formatted(LISTS, priorityId, transferId);
  }

  private static String schemaType(String field) {
    return "string";
  }

  private static JsonNode step(JsonNode document, String id) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (id.equals(step.path("id").asText())) {
        return step;
      }
    }
    throw new AssertionError("Missing step " + id);
  }

  private static int serviceCalls(JsonNode document) {
    int count = 0;
    for (JsonNode step : document.path("flow").path("steps")) {
      if ("SERVICE_CALL".equals(step.path("kind").asText())) {
        count++;
      }
    }
    return count;
  }

  private static List<JsonNode> rules(JsonNode document) {
    List<JsonNode> rules = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          rules.add(rule);
        }
      }
    }
    return rules;
  }

  private static String behaviors(List<JsonNode> rules) {
    StringBuilder text = new StringBuilder();
    for (JsonNode rule : rules) {
      text.append(rule.path("behavior").asText()).append('\n');
    }
    return text.toString();
  }

  private static List<String> requestTargets(JsonNode call) {
    List<String> leaves = new ArrayList<>();
    for (JsonNode transfer : call.path("data").path("transfers")) {
      if (!"request".equals(transfer.path("targetPort").path("portName").asText())) {
        continue;
      }
      for (JsonNode rule : transfer.path("rules")) {
        if ("OUTBOUND_REQUEST".equals(rule.path("target").path("port").asText())) {
          leaves.add(leaf(rule.path("target").path("fieldPath").asText()));
        }
      }
    }
    return leaves;
  }

  private static List<String> retainedLeaves(JsonNode document) {
    List<String> leaves = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        leaves.add(leaf(value.path("source").path("fieldPath").asText()));
      }
    }
    return leaves;
  }

  private static List<String> sourcePaths(JsonNode document) {
    List<String> paths = new ArrayList<>();
    for (JsonNode rule : rules(document)) {
      for (JsonNode source : rule.path("sources")) {
        paths.add(source.path("fieldPath").asText());
      }
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        paths.add(value.path("source").path("fieldPath").asText());
      }
    }
    return paths;
  }

  private static List<String> targets(JsonNode document) {
    List<String> paths = new ArrayList<>();
    for (JsonNode rule : rules(document)) {
      paths.add(rule.path("target").path("fieldPath").asText());
    }
    return paths;
  }

  private static JsonNode ruleTargeting(List<JsonNode> rules, String path) {
    for (JsonNode rule : rules) {
      if (path.equals(rule.path("target").path("fieldPath").asText())) {
        return rule;
      }
    }
    throw new AssertionError("Missing rule " + path);
  }

  private static String ruleId(JsonNode document, String path) {
    return ruleTargeting(rules(document), path).path("id").asText();
  }

  private static String transferIdOf(JsonNode document, String ruleId) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          if (ruleId.equals(rule.path("id").asText())) {
            return transfer.path("id").asText();
          }
        }
      }
    }
    throw new AssertionError("Missing transfer for " + ruleId);
  }

  private static String behaviorOf(JsonNode document, String id) {
    for (JsonNode rule : rules(document)) {
      if (id.equals(rule.path("id").asText())) {
        return rule.path("behavior").asText();
      }
    }
    throw new AssertionError("Missing rule " + id);
  }

  private static String targetOf(JsonNode document, String id) {
    for (JsonNode rule : rules(document)) {
      if (id.equals(rule.path("id").asText())) {
        return rule.path("target").path("fieldPath").asText();
      }
    }
    throw new AssertionError("Missing rule " + id);
  }

  private static String leaf(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }

  private static String seededDocument() {
    return """
        {
          "schemaVersion": 1,
          "documentId": "doc-map",
          "sources": [{
            "id": "src-map",
            "role": "MAPPING",
            "contentReference": "artifact://mapping",
            "contentHash": "hash-map",
            "originalName": "mapping.txt",
            "suppliedIdentifier": "MAP-1",
            "correctionOf": []
          }],
          "requirements": [],
          "flow": {
            "steps": [
              {"id":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the order event","sourceIds":["src-map"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}},
              {"id":"create","kind":"SERVICE_CALL","label":"Task","intent":"Create the Salesforce task","sourceIds":["src-map"],"requirementIds":[],"binding":{"catalogId":"sys-wfm","version":"2024.4","operationId":"createTask","protocol":"http","method":"POST","path":"/wfm/v1/tasks","contractReferences":["spec-create"],"exposedPorts":["payload","request","success","failure"]},"data":{"transfers":[],"retainedValues":[]}},
              {"id":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the outcome","sourceIds":["src-map"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}}
            ],
            "connections": [
              {"id":"c-ok","sourceStepId":"create","outcome":"success","targetStepId":"result","routingIntent":"return success","evidenceIds":["src-map"]},
              {"id":"c-fail","sourceStepId":"create","outcome":"failure","targetStepId":"result","routingIntent":"return failure","evidenceIds":["src-map"]}
            ],
            "sequenceGroups": [],
            "conditionGroups": [],
            "splitGroups": [],
            "loopGroups": [],
            "retryGroups": [],
            "errorScopeGroups": []
          },
          "progress": {"tasks":[],"findings":[],"questions":[],"approvalReference":"","derivedResultReferences":[],"recheckStages":[]}
        }
        """;
  }
}
