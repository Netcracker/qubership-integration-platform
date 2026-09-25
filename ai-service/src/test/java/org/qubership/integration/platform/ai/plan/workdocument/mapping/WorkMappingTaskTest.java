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
  private static final String REQUEST = "xfer-request";
  private static final String REPLY = "xfer-reply";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

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
  void suppliedMappingStaysLinkedWithoutCopyingRetainedFieldsOntoTheRequest() {
    WorkCommit commit = mapping.interpret(RUN_ID, REQUEST, materials(false), request -> requestRules());
    mapping.interpret(RUN_ID, REPLY, materials(false), request -> replyRules());

    assertEquals("PREPARED", commit.outcome().name());
    JsonNode document = JSON.valueToTree(documents.read(RUN_ID).document());
    JsonNode call = step(document, "create");
    assertEquals(1, serviceCalls(document));
    List<JsonNode> stored = rules(document);
    assertTrue(stored.size() >= 5);
    for (JsonNode rule : stored) {
      assertTrue(rule.path("evidenceIds").toString().contains("src-map"));
    }
    assertTrue(behaviors(stored).contains("SALESFORCE_TASK_CREATE_ERROR"));
    assertFalse(requestTargets(call).contains("executionId"));
    assertFalse(requestTargets(call).contains("orderId"));
    assertFalse(requestTargets(call).contains("processInstanceId"));
    assertFalse(requestTargets(call).contains("executionNumber"));
    assertFalse(requestTargets(call).contains("taskId"));
    assertTrue(retainedLeaves(document).contains("processInstanceId"));
    JsonNode description = ruleTargeting(stored, "$.Description");
    assertEquals("$.Description", description.path("target").path("fieldPath").asText());
    assertTrue(description.path("behavior").asText().contains("string"));
  }

  @Test
  void retainedRenameRequiresARecordedRelationship() {
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () -> mapping.interpret(RUN_ID, REPLY, materials(true), request -> processIdCapture(false)));

    assertEquals("UNEVIDENCED_MAPPING", rejected.code());
    assertFalse(targets(JSON.valueToTree(documents.read(RUN_ID).document())).contains("$.processId"));
    assertTrue(questions(documents.read(RUN_ID)).isEmpty());

    WorkCommit accepted =
        mapping.interpret(RUN_ID, REPLY, materials(true), request -> processIdCapture(true));
    assertEquals("PREPARED", accepted.outcome().name());
    assertTrue(targets(JSON.valueToTree(accepted.state().document())).contains("$.processId"));
  }

  @Test
  void clarificationKeepsTheAcceptedSiblingAndNamesBothFields() {
    mapping.interpret(RUN_ID, REQUEST, materials(false), request -> subjectRule());
    WorkCommit asked =
        mapping.interpret(RUN_ID, REPLY, materials(false), request -> processQuestion());

    assertEquals("NEEDS_CLARIFICATION", asked.outcome().name());
    JsonNode document = JSON.valueToTree(asked.state().document());
    assertTrue(targets(document).contains("$.Subject"));
    assertFalse(targets(document).contains("$.processId"));
    JsonNode question = document.path("progress").path("questions").get(0);
    assertEquals("FIELD_RELATIONSHIP", question.path("choice").asText());
    assertTrue(question.toString().contains("processId"));
    assertTrue(question.toString().contains("processInstanceId"));
    assertTrue(retainedLeaves(document).contains("processInstanceId"));
  }

  @Test
  void unknownPathsAndFabricatedPrefixesStayOutOfTheDocument() {
    assertEquals(
        "FABRICATED_PREFIX",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> mapping.interpret(RUN_ID, REQUEST, materials(false), request -> targetRule("$.Task.Description")))
            .code());
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> mapping.interpret(RUN_ID, REQUEST, materials(false), request -> targetRule("$.id")))
            .code());
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID, REQUEST, materials(false), request -> sourceRule("$.id", "$.Subject")))
            .code());
    JsonNode document = JSON.valueToTree(documents.read(RUN_ID).document());
    assertEquals(0, rules(document).size());
    assertTrue(questions(documents.read(RUN_ID)).isEmpty());
    assertEquals(1, serviceCalls(document));
    assertFalse(targets(document).contains("$.Task.Description"));
    assertFalse(targets(document).contains("$.id"));
  }

  @Test
  void barePropertyPublishesInContractForm() {
    WorkCommit commit =
        mapping.interpret(RUN_ID, REQUEST, materials(false), request -> targetRule("Subject"));

    JsonNode target = rules(JSON.valueToTree(commit.state().document())).getFirst().path("target");
    assertEquals("request", target.path("port").asText());
    assertEquals("$.Subject", target.path("fieldPath").asText());
    assertEquals(REQUEST, step(JSON.valueToTree(commit.state().document()), "create").path("data").path("transfers").get(0).path("id").asText());
  }

  @Test
  void extraStepsAreRejectedAndThePromptNamesIds() {
    List<String> prompts = new ArrayList<>();
    assertEquals(
        "EXTRA_PROPERTY",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID,
                        REQUEST,
                        materials(false),
                        request -> {
                          prompts.add(request.prompt());
                          return targetRule("$.Subject").replace("\"rules\"", "\"steps\":[],\"rules\"");
                        }))
            .code());
    assertEquals(3, JSON.valueToTree(documents.read(RUN_ID).document()).path("flow").path("steps").size());
    assertEquals(1, serviceCalls(JSON.valueToTree(documents.read(RUN_ID).document())));
    String prompt = prompts.getFirst();
    assertTrue(prompt.contains("id start kind TRIGGER label onTaskStart"));
    assertTrue(prompt.contains("id create kind SERVICE_CALL label Task"));
    assertTrue(prompt.contains("id result kind REPLY label onTaskResult"));
    assertFalse(prompt.contains("step create SERVICE_CALL Task"));
    assertTrue(prompt.contains("A label is not a source ref."));
    assertTrue(prompt.contains("Do not send a step"));
    assertFalse(prompt.contains("`completeTask` is the `commandType` constant"));
  }

  @Test
  void labelsAreNotRewrittenToStepIds() {
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> mapping.interpret(RUN_ID, REQUEST, materials(false), request -> labeledSource()))
            .code());
    JsonNode document = JSON.valueToTree(documents.read(RUN_ID).document());
    assertEquals(0, rules(document).size());
    assertEquals(3, document.path("flow").path("steps").size());
  }

  @Test
  void dollarPathIsRejectedWithoutAQuestion() {
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> mapping.interpret(RUN_ID, REQUEST, materials(false), request -> sourceRule("$", "$.Subject")))
            .code());
    JsonNode document = JSON.valueToTree(documents.read(RUN_ID).document());
    assertEquals(0, rules(document).size());
    assertTrue(questions(documents.read(RUN_ID)).isEmpty());
    assertFalse(sourcePaths(document).contains("$"));
  }

  @Test
  void requestPromptKeepsTheAssignedSchemaAndOmitsTheReplySchema() {
    List<String> prompts = new ArrayList<>();
    mapping.interpret(
        RUN_ID,
        REQUEST,
        materials(false),
        request -> {
          prompts.add(request.prompt());
          return requestRules();
        });

    String prompt = prompts.getFirst();
    assertTrue(prompt.contains("supplied mapping"));
    assertTrue(prompt.contains("Do not invent a prefix."));
    assertTrue(prompt.contains("transfer " + REQUEST));
    assertTrue(prompt.contains("source start/payload"));
    assertTrue(prompt.contains("PREPARED contains the rules"));
    assertTrue(prompt.contains("It contains no rules."));
    assertTrue(prompt.contains("Put fallback, formatting, and failure text in behavior."));
    assertTrue(prompt.contains("written as $.Property"));
    assertTrue(prompt.contains("Do not use a lone $."));
    int field = prompt.indexOf("orderCreationDate");
    assertTrue(field >= 0);
    int lineStart = prompt.lastIndexOf('\n', field);
    int lineEnd = prompt.indexOf('\n', field);
    String schemaLine =
        prompt.substring(lineStart < 0 ? 0 : lineStart + 1, lineEnd < 0 ? prompt.length() : lineEnd);
    assertTrue(schemaLine.contains("start"));
    assertFalse(prompt.contains("commandType"));
  }

  @Test
  void textThatNamesBothFieldsDoesNotAuthorizeTheRename() {
    WorkTaskMaterials named =
        new WorkTaskMaterials(
            materials(false).schemas(),
            List.of("processId is the retained processInstanceId"),
            Map.of("src-map", "Keep processInstanceId for the response."));
    assertEquals(
        "UNEVIDENCED_MAPPING",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> mapping.interpret(RUN_ID, REPLY, named, request -> processIdCapture(false)))
            .code());
    assertFalse(targets(JSON.valueToTree(documents.read(RUN_ID).document())).contains("$.processId"));
  }

  @Test
  void priorityRepairLeavesEveryOtherRuleAndIdUnchanged() throws Exception {
    mapping.interpret(RUN_ID, REQUEST, materials(false), request -> requestRules());
    String before = JSON.writeValueAsString(documents.read(RUN_ID).document());
    JsonNode priorTree = JSON.readTree(before);
    String priorityId = ruleId(priorTree, "$.Priority");

    WorkCommit repaired =
        mapping.repair(
            RUN_ID,
            priorityId,
            materials(false),
            request ->
                """
                {"outcome":"PREPARED","rules":[{"existingId":"%s","targetPath":"$.Priority","sources":[{"sourceRef":"start/payload","fieldPath":"$.priority"}],"constants":[],"behavior":"urgent maps to High","evidenceRefs":["src-map"]}],"decision":"","evidenceRefs":[]}
                """
                    .formatted(priorityId));

    JsonNode after = JSON.valueToTree(repaired.state().document());
    assertEquals("urgent maps to High", behaviorOf(after, priorityId));
    for (JsonNode rule : rules(JSON.readTree(before))) {
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
                    REQUEST,
                    materials(false),
                    request -> {
                      throw new OutputParsingException("Mapping capture could not be parsed.", null);
                    }));
    assertEquals("MALFORMED_CAPTURE", rejected.code());
    assertEquals(0, rules(JSON.valueToTree(documents.read(RUN_ID).document())).size());
    assertTrue(questions(documents.read(RUN_ID)).isEmpty());
  }

  @Test
  void emptyRulesAreRejectedAndNoMappingKeepsTheTransfer() {
    assertEquals(
        "UNEVIDENCED_MAPPING",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID,
                        REQUEST,
                        materials(false),
                        request ->
                            "{\"outcome\":\"PREPARED\",\"rules\":[],\"decision\":\"\",\"evidenceRefs\":[]}"))
            .code());
    assertTrue(questions(documents.read(RUN_ID)).isEmpty());

    WorkCommit decided =
        mapping.interpret(
            RUN_ID,
            REQUEST,
            materials(false),
            request ->
                "{\"outcome\":\"PREPARED\",\"rules\":[],\"decision\":\"NO_MAPPING\",\"evidenceRefs\":[\"src-map\"]}");

    JsonNode transfer = step(JSON.valueToTree(decided.state().document()), "create").path("data").path("transfers").get(0);
    assertEquals(REQUEST, transfer.path("id").asText());
    assertEquals("NO_MAPPING", transfer.path("decision").asText());
    assertEquals("start", transfer.path("sourcePorts").get(0).path("stepId").asText());
    assertEquals("create", transfer.path("targetPort").path("stepId").asText());
    assertEquals(0, transfer.path("rules").size());
  }

  @Test
  void oneCallThatIncludesAnotherTransferIsRejectedBeforePublication() {
    assertEquals(
        "EXTRA_PROPERTY",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID,
                        REQUEST,
                        materials(false),
                        request ->
                            "{\"outcome\":\"PREPARED\",\"transfers\":[{\"alias\":\"xfer-reply\"}],\"rules\":[],\"decision\":\"\",\"evidenceRefs\":[]}"))
            .code());
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

  private static String requestRules() {
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-subject","targetPath":"$.Subject","sources":[{"sourceRef":"start/payload","fieldPath":"$.name"},{"sourceRef":"start/payload","fieldPath":"$.subRequestType"},{"sourceRef":"start/payload","fieldPath":"$.orderId"}],"constants":[],"behavior":"name, or a formatted fallback","evidenceRefs":["src-map"]},
          {"alias":"rule-priority","targetPath":"$.Priority","sources":[{"sourceRef":"start/payload","fieldPath":"$.priority"}],"constants":[],"behavior":"high, urgent, or critical to High","evidenceRefs":["src-map"]},
          {"alias":"rule-status","targetPath":"$.Status","sources":[],"constants":[{"name":"status","value":"Not Started"}],"behavior":"constant Not Started","evidenceRefs":["src-map"]},
          {"alias":"rule-activity","targetPath":"$.ActivityDate","sources":[{"sourceRef":"start/payload","fieldPath":"$.parameters.orderCreationDate"}],"constants":[],"behavior":"order creation date, else today","evidenceRefs":["src-map"]},
          {"alias":"rule-description","targetPath":"$.Description","sources":[{"sourceRef":"start/payload","fieldPath":"$.taskId"}],"constants":[],"behavior":"serialized string","evidenceRefs":["src-map"]}
        ],"decision":"","evidenceRefs":[]}
        """;
  }

  private static String replyRules() {
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-command","targetPath":"$.commandType","sources":[],"constants":[{"name":"commandType","value":"completeTask"}],"behavior":"constant completeTask","evidenceRefs":["src-map"]},
          {"alias":"rule-failure","targetPath":"$.error.code","sources":[{"sourceRef":"create/failure","fieldPath":"$.status"}],"constants":[{"name":"code","value":"SALESFORCE_TASK_CREATE_ERROR"}],"behavior":"SALESFORCE_TASK_CREATE_ERROR plus the failure text","evidenceRefs":["src-map"]}
        ],"decision":"","evidenceRefs":[]}
        """;
  }

  private static String subjectRule() {
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-subject","targetPath":"$.Subject","sources":[{"sourceRef":"start/payload","fieldPath":"$.name"}],"constants":[],"behavior":"name","evidenceRefs":["src-map"]}
        ],"decision":"","evidenceRefs":[]}
        """;
  }

  private static String processIdCapture(boolean relationship) {
    String link =
        relationship
            ? ",\"relationship\":{\"sourceField\":\"processInstanceId\",\"targetField\":\"processId\",\"evidenceRefs\":[\"src-map\"]}"
            : "";
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-process","targetPath":"$.processId","sources":[{"sourceRef":"retained/keep-process","fieldPath":"$.processInstanceId"}],"constants":[],"behavior":"processId reads retained processInstanceId","evidenceRefs":["src-map"]%s}
        ],"decision":"","evidenceRefs":[]}
        """.formatted(link);
  }

  private static String processQuestion() {
    return """
        {"outcome":"NEEDS_CLARIFICATION","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"Field processId does not match source processInstanceId. Record the relationship or choose another field.","choiceKind":"FIELD_RELATIONSHIP","sourceStepId":"start","sourcePort":"payload","sourceField":"processInstanceId","sourceRetainedId":"keep-process","targetStepId":"result","targetPort":"request","targetField":"processId","targetRetainedId":"","evidenceRefs":["src-map"]}}
        """;
  }

  private static String targetRule(String path) {
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-path","targetPath":"%s","sources":[{"sourceRef":"start/payload","fieldPath":"$.name"}],"constants":[],"behavior":"written field","evidenceRefs":["src-map"]}
        ],"decision":"","evidenceRefs":[]}
        """
        .formatted(path);
  }

  private static String sourceRule(String sourcePath, String targetPath) {
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-path","targetPath":"%s","sources":[{"sourceRef":"start/payload","fieldPath":"%s"}],"constants":[],"behavior":"read field","evidenceRefs":["src-map"]}
        ],"decision":"","evidenceRefs":[]}
        """
        .formatted(targetPath, sourcePath);
  }

  private static String labeledSource() {
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-subject","targetPath":"$.Subject","sources":[{"sourceRef":"onTaskStart/payload","fieldPath":"$.name"}],"constants":[],"behavior":"name","evidenceRefs":["src-map"]}
        ],"decision":"","evidenceRefs":[]}
        """;
  }

  private static JsonNode questions(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("progress").path("questions");
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
        if ("request".equals(rule.path("target").path("port").asText())) {
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
          "schemaVersion": 2,
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
              {"id":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the order event","sourceIds":["src-map"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[
                {"id":"keep-execution","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.executionId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-map"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-order","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.orderId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-map"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-process","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.processInstanceId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-map"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-number","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.executionNumber","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-map"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-task","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.taskId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-map"],"producerStepId":"start","resolution":"RESOLVED"}
              ]}},
              {"id":"create","kind":"SERVICE_CALL","label":"Task","intent":"Create the Salesforce task","sourceIds":["src-map"],"requirementIds":[],"binding":{"catalogId":"sys-wfm","version":"2024.4","operationId":"createTask","protocol":"http","method":"POST","path":"/wfm/v1/tasks","contractReferences":["spec-create"],"exposedPorts":["payload","request","success","failure"]},"data":{"transfers":[
                {"id":"xfer-request","sourcePorts":[{"stepId":"start","portName":"payload"}],"targetPort":{"stepId":"create","portName":"request"},"requirementIds":[],"rules":[],"decision":"UNSPECIFIED","outcome":"UNSPECIFIED","requiredRetainedIds":[]}
              ],"retainedValues":[]}},
              {"id":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the outcome","sourceIds":["src-map"],"requirementIds":[],"binding":null,"data":{"transfers":[
                {"id":"xfer-reply","sourcePorts":[{"stepId":"create","portName":"success"},{"stepId":"create","portName":"failure"}],"targetPort":{"stepId":"result","portName":"request"},"requirementIds":[],"rules":[],"decision":"UNSPECIFIED","outcome":"UNSPECIFIED","requiredRetainedIds":["keep-process"]}
              ],"retainedValues":[]}}
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
