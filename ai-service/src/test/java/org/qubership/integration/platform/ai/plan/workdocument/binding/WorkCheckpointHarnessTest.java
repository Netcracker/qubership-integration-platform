package org.qubership.integration.platform.ai.plan.workdocument.binding;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.CheckpointRequest;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.CheckpointSession;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.WorkCheckpointHarness;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

class WorkCheckpointHarnessTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SECRET = "sk-live-secret-value";

  @TempDir Path temp;

  @Test
  void logicalCheckpointCallsDesignAndRecordsTheConfiguredModel() throws Exception {
    List<String> prompts = new ArrayList<>();
    AtomicInteger catalogCalls = new AtomicInteger();
    Path report = temp.resolve("sync-result.json");

    int exit =
        WorkCheckpointHarness.run(
            request("logical", "sync-result", report),
            session(prompts, catalogCalls, capture("sync-result")));

    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(0, exit, Files.readString(report));
    assertEquals("logical", body.path("checkpoint").asText());
    assertEquals("sync-result", body.path("caseId").asText());
    assertEquals("G1", body.path("gate").asText());
    assertEquals("configured-provider", body.path("effectiveProvider").asText());
    assertEquals("configured-model", body.path("effectiveModel").asText());
    assertEquals("gpt-6-luna", body.path("gateModel").asText());
    assertFalse(body.path("providerSwitched").asBoolean());
    assertFalse(body.path("materialized").asBoolean());
    assertEquals(0, catalogCalls.get());
    assertTrue(prompts.get(0).contains("sync-result"));
    assertEquals("logical-design", body.path("taskScope").path("skillId").asText());
    assertFalse(body.path("documentRevision").asText().isBlank());
    assertTrue(body.path("attempts").asInt() >= 1);
    assertEquals("PREPARED", body.path("outcome").asText());
    assertFalse(body.path("sanitizedResponse").asText().contains(SECRET));
    assertFalse(Files.readString(report).contains(SECRET));
    assertTrue(body.path("requiredObservation").asText().toLowerCase().contains("result"));
  }

  @Test
  void g1LogicalCasesStayDistinct() throws Exception {
    List<String> observations = new ArrayList<>();
    for (String caseId : List.of("sync-result", "async-callback", "repeat-call")) {
      Path report = temp.resolve(caseId + ".json");
      WorkCheckpointHarness.run(
          request("logical", caseId, report),
          session(new ArrayList<>(), new AtomicInteger(), capture(caseId)));
      JsonNode body = JSON.readTree(report.toFile());
      assertEquals(caseId, body.path("caseId").asText());
      assertEquals("PREPARED", body.path("outcome").asText());
      assertEquals("logical", body.path("checkpoint").asText());
      assertEquals("gpt-6-luna", body.path("gateModel").asText());
      observations.add(body.path("requiredObservation").asText());
    }
    assertEquals(3, observations.stream().distinct().count());
    JsonNode cases = JSON.readTree(fixture("g1-cases.json").toFile());
    List<String> ids = new ArrayList<>();
    cases.path("cases").forEach(item -> ids.add(item.path("id").asText()));
    assertEquals(List.of("sync-result", "async-callback", "repeat-call", "om-bindings"), ids);
  }

  @Test
  void bindingCheckpointCallsSelectAndDoesNotMaterialize() throws Exception {
    AtomicInteger catalogCalls = new AtomicInteger();
    Path report = temp.resolve("om-bindings.json");

    int exit =
        WorkCheckpointHarness.run(
            request("binding", "om-bindings", report),
            session(new ArrayList<>(), catalogCalls, selection()));

    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(0, exit);
    assertEquals("binding", body.path("checkpoint").asText());
    assertEquals("om-bindings", body.path("caseId").asText());
    assertEquals(1, catalogCalls.get());
    assertFalse(body.path("materialized").asBoolean());
    assertFalse(body.path("providerSwitched").asBoolean());
    assertEquals("configured-model", body.path("effectiveModel").asText());
    assertTrue(body.path("attempts").asInt() >= 1);
    assertEquals("POST", body.path("resolvedMethod").asText());
    assertEquals("/wfm/v1/tasks", body.path("resolvedPath").asText());
    assertFalse(Files.readString(report).contains(SECRET));
  }

  @Test
  void mappingCheckpointRunsSuppliedMappingAndPriorityRepair() throws Exception {
    Path supplied = temp.resolve("om-mapping.json");
    int suppliedExit =
        WorkCheckpointHarness.run(
            request("mapping", "om-mapping", supplied),
            session(new ArrayList<>(), new AtomicInteger(), omMappingCapture()));
    JsonNode suppliedBody = JSON.readTree(supplied.toFile());
    assertEquals(0, suppliedExit, Files.readString(supplied));
    assertEquals("PREPARED", suppliedBody.path("outcome").asText(), Files.readString(supplied));
    assertEquals("om-mapping", suppliedBody.path("caseId").asText());
    assertEquals("G2", suppliedBody.path("gate").asText());
    assertEquals("data-mapping", suppliedBody.path("taskScope").path("skillId").asText());
    assertEquals("mapping-initial", suppliedBody.path("taskScope").path("taskId").asText());
    assertTrue(suppliedBody.path("requiredObservation").asText().contains("retained-context"));

    Path repair = temp.resolve("priority-repair.json");
    int repairExit =
        WorkCheckpointHarness.run(
            request("mapping", "priority-repair", repair),
            session(new ArrayList<>(), new AtomicInteger(), priorityRepairCapture()));
    JsonNode repairBody = JSON.readTree(repair.toFile());
    assertEquals(0, repairExit, Files.readString(repair));
    assertEquals("priority-repair", repairBody.path("caseId").asText());
    assertEquals("mapping-repair-rule-priority", repairBody.path("taskScope").path("taskId").asText());
    assertTrue(repairBody.path("requiredObservation").asText().contains("Priority"));
    assertTrue(repairBody.path("sanitizedResponse").asText().contains("urgent maps to High"));
  }

  @Test
  void missingCapabilitiesFailWithoutCallingTheModel() throws Exception {
    for (String checkpoint : List.of("recovery")) {
      List<String> prompts = new ArrayList<>();
      AtomicInteger catalogCalls = new AtomicInteger();
      Path report = temp.resolve(checkpoint + ".json");
      int exit =
          WorkCheckpointHarness.run(
              request(checkpoint, "om-mapping", report),
              session(prompts, catalogCalls, capture("unused")));
      JsonNode body = JSON.readTree(report.toFile());
      assertEquals(1, exit);
      assertEquals("FAILED", body.path("outcome").asText());
      assertEquals("MISSING_CAPABILITY", body.path("failureCode").asText());
      assertEquals(checkpoint, body.path("checkpoint").asText());
      assertTrue(prompts.isEmpty());
      assertEquals(0, catalogCalls.get());
      assertFalse(body.path("materialized").asBoolean());
    }
  }

  @Test
  void scriptRefusesAProviderCallUntilLiveIsExplicit() throws Exception {
    Path report = temp.resolve("refused.json");
    Path bin = temp.resolve("bin");
    Files.createDirectories(bin);
    Path touched = temp.resolve("network-tool-ran");
    Files.writeString(bin.resolve("curl"), "#!/bin/sh\ntouch '" + touched + "'\nexit 0\n");
    Files.writeString(bin.resolve("mvn"), "#!/bin/sh\ntouch '" + touched + "'\nexit 0\n");
    Files.writeString(bin.resolve("mvnw"), "#!/bin/sh\ntouch '" + touched + "'\nexit 0\n");
    bin.resolve("curl").toFile().setExecutable(true);
    bin.resolve("mvn").toFile().setExecutable(true);
    bin.resolve("mvnw").toFile().setExecutable(true);

    ProcessBuilder builder =
        new ProcessBuilder(
            "bash",
            script().toString(),
            "--checkpoint",
            "logical",
            "--case",
            "sync-result",
            "--report",
            report.toString());
    builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    builder.environment().remove("WORK_CHECKPOINT_LIVE");
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();

    assertEquals(2, exit, output);
    assertFalse(Files.exists(touched), output);
    JsonNode body = JSON.readTree(report.toFile());
    assertEquals("REFUSED", body.path("outcome").asText());
    assertEquals("LIVE_NOT_ENABLED", body.path("failureCode").asText());
    assertEquals("logical", body.path("checkpoint").asText());
    assertEquals("sync-result", body.path("caseId").asText());
    assertFalse(Files.readString(report).contains(SECRET));
    String script = Files.readString(script());
    assertFalse(script.contains("LLM_CHAT_MODEL="));
    assertFalse(script.contains("--stop-after"));
    assertFalse(script.contains("CHAIN_MATERIALIZED"));
  }

  @Test
  void scriptFailsMappingBeforeAnyTool() throws Exception {
    Path report = temp.resolve("mapping.json");
    ProcessBuilder builder =
        new ProcessBuilder(
            "bash",
            script().toString(),
            "--checkpoint",
            "recovery",
            "--case",
            "wrong-binding-recovery",
            "--report",
            report.toString());
    builder.environment().put("WORK_CHECKPOINT_LIVE", "1");
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    assertEquals(1, exit, output);
    JsonNode body = JSON.readTree(report.toFile());
    assertEquals("MISSING_CAPABILITY", body.path("failureCode").asText());
    assertEquals("recovery", body.path("checkpoint").asText());
  }

  @Test
  void livePublicationReloadsFromTheRunStoreAndOfflineDoesNotOpenAProvider() throws Exception {
    Path directory = temp.resolve("blobs");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(directory);
    Path report = temp.resolve("durable.json");
    AtomicInteger providerCalls = new AtomicInteger();

    int exit =
        WorkCheckpointHarness.run(
            new CheckpointRequest("logical", "sync-result", report, fixtureRoot(), true, store),
            session(new ArrayList<>(), new AtomicInteger(), capture("sync-result"), providerCalls));

    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(0, exit, Files.readString(report));
    assertTrue(body.path("durable").asBoolean());
    ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    Clock clock = Clock.systemUTC();
    ArtifactBlobStore reopened = WorkCheckpointHarness.openDurableStore(directory);
    ProductPipelineRunStore runs = new ProductPipelineRunStore(reopened, json, clock);
    WorkDocumentService documents =
        new WorkDocumentService(runs, new CompilationArtifacts(reopened, json, clock), json);
    WorkDocumentState reloaded = documents.read("checkpoint-sync-result");
    assertEquals(body.path("documentRevision").asText(), reloaded.revision());

    Path offline = temp.resolve("offline.json");
    AtomicInteger offlineCalls = new AtomicInteger();
    int refused =
        WorkCheckpointHarness.run(
            new CheckpointRequest("logical", "sync-result", offline, fixtureRoot(), false, null),
            session(new ArrayList<>(), new AtomicInteger(), capture("sync-result"), offlineCalls));
    assertEquals(2, refused);
    assertEquals(0, offlineCalls.get());
    assertFalse(JSON.readTree(offline.toFile()).path("durable").asBoolean());

    Path blocked = temp.resolve("not-a-directory");
    Files.writeString(blocked, "x");
    IllegalStateException unavailable =
        assertThrows(IllegalStateException.class, () -> WorkCheckpointHarness.openDurableStore(blocked));
    assertTrue(unavailable.getMessage().startsWith("STORE_UNAVAILABLE"));
    AtomicInteger memoryCalls = new AtomicInteger();
    Path memoryReport = temp.resolve("memory.json");
    int memory =
        WorkCheckpointHarness.run(
            new CheckpointRequest(
                "logical",
                "sync-result",
                memoryReport,
                fixtureRoot(),
                true,
                new InMemoryArtifactBlobStore()),
            session(new ArrayList<>(), new AtomicInteger(), capture("sync-result"), memoryCalls));
    assertEquals(1, memory);
    assertEquals("STORE_UNAVAILABLE", JSON.readTree(memoryReport.toFile()).path("failureCode").asText());
    assertEquals(0, memoryCalls.get());
  }

  private static CheckpointRequest request(String checkpoint, String caseId, Path report) {
    return new CheckpointRequest(checkpoint, caseId, report, fixtureRoot(), true, null);
  }

  private static CheckpointSession session(
      List<String> prompts, AtomicInteger catalogCalls, String modelOutput) {
    return session(prompts, catalogCalls, modelOutput, new AtomicInteger());
  }

  private static CheckpointSession session(
      List<String> prompts, AtomicInteger catalogCalls, String modelOutput, AtomicInteger providerCalls) {
    CatalogResolution catalog =
        new CatalogResolution() {
          @Override
          public CatalogLookup lookup(String operationHint, String pinnedVersion) {
            catalogCalls.incrementAndGet();
            return new CatalogLookup.Hit(
                new CatalogHit(
                    operationHint,
                    "sys-wfm",
                    "group-create",
                    "spec-create",
                    "2024.4",
                    "op-create",
                    "http",
                    "POST",
                    "/wfm/v1/tasks",
                    List.of("request", "success", "failure")));
          }

          @Override
          public ApiHubHit searchApiHub(String interactionId, String operationHint, String pinnedVersion) {
            catalogCalls.incrementAndGet();
            return null;
          }

          @Override
          public void importContract(ApiHubHit hit) {
            catalogCalls.incrementAndGet();
          }
        };
    return new CheckpointSession(
        "configured-provider",
        "configured-model",
        false,
        prompt -> {
          providerCalls.incrementAndGet();
          prompts.add(prompt);
          return modelOutput.replace("SECRET_SLOT", SECRET);
        },
        catalog);
  }

  private static String capture(String caseId) {
    String steps =
        switch (caseId) {
          case "async-callback" ->
              """
              "steps":[
                {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive SECRET_SLOT","sourceRefs":["src-om"],"requirementRefs":[]},
                {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create","sourceRefs":["src-om"],"requirementRefs":[]},
                {"existingId":"","alias":"callback","kind":"TRIGGER","label":"callback","intent":"Independent callback","sourceRefs":["src-om"],"requirementRefs":[]}
              ],
              "connections":[
                {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Call","evidenceRefs":["src-om"]},
                {"existingId":"","alias":"back","sourceStepRef":"callback","outcome":"correlation","targetStepRef":"create","routingIntent":"Correlate","evidenceRefs":["src-om"]}
              ]
              """;
          case "repeat-call" ->
              """
              "steps":[
                {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive SECRET_SLOT","sourceRefs":["src-om"],"requirementRefs":[]},
                {"existingId":"","alias":"first","kind":"SERVICE_CALL","label":"createTask","intent":"First call","sourceRefs":["src-om"],"requirementRefs":[]},
                {"existingId":"","alias":"second","kind":"SERVICE_CALL","label":"createTask","intent":"Second call","sourceRefs":["src-om"],"requirementRefs":[]}
              ],
              "connections":[
                {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"first","routingIntent":"First","evidenceRefs":["src-om"]},
                {"existingId":"","alias":"again","sourceStepRef":"first","outcome":"success","targetStepRef":"second","routingIntent":"Second","evidenceRefs":["src-om"]}
              ]
              """;
          default ->
              """
              "steps":[
                {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive SECRET_SLOT","sourceRefs":["src-om"],"requirementRefs":[]},
                {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create","sourceRefs":["src-om"],"requirementRefs":[]},
                {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return","sourceRefs":["src-om"],"requirementRefs":[]}
              ],
              "connections":[
                {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Call","evidenceRefs":["src-om"]},
                {"existingId":"","alias":"ok","sourceStepRef":"create","outcome":"success","targetStepRef":"result","routingIntent":"Success","evidenceRefs":["src-om"]},
                {"existingId":"","alias":"bad","sourceStepRef":"create","outcome":"failure","targetStepRef":"result","routingIntent":"Failure","evidenceRefs":["src-om"]}
              ]
              """;
        };
    return "{\"outcome\":\"PREPARED\","
        + steps
        + ",\"requirements\":[],\"sequenceGroups\":[],\"conditionGroups\":[],\"splitGroups\":[],\"loopGroups\":[],\"retryGroups\":[],\"errorScopeGroups\":[],\"transfers\":[],\"rules\":[],\"retainedValues\":[],\"deletes\":[],"
        + "\"question\":\"\",\"unresolvedChoice\":\"\",\"clarificationEvidenceIds\":[],\"defectRecordRef\":\"\",\"contradiction\":\"\",\"defectEvidenceIds\":[],\"issueCategory\":\"\"}";
  }

  private static String omMappingCapture() {
    return """
        {"outcome":"PREPARED","requirements":[],"steps":[],"connections":[],"sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"deletes":[],"transfers":[
          {"existingId":"","alias":"xfer-request","targetStepRef":"create","sourcePorts":[{"stepId":"start","portName":"payload"}],"targetPort":{"stepId":"create","portName":"request"},"requirementRefs":[],"decision":""},
          {"existingId":"","alias":"xfer-response","targetStepRef":"result","sourcePorts":[{"stepId":"create","portName":"success"}],"targetPort":{"stepId":"result","portName":"request"},"requirementRefs":[],"decision":""}
        ],"rules":[
          {"existingId":"","alias":"rule-subject","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.name","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Subject","retainedValueId":""},"constants":[],"behavior":"name or fallback","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-priority","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.priority","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Priority","retainedValueId":""},"constants":[],"behavior":"high, urgent, or critical to High","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-status","transferRef":"xfer-request","sources":[],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Status","retainedValueId":""},"constants":[{"name":"status","value":"Not Started"}],"behavior":"constant Not Started","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-activity","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.parameters.orderCreationDate","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.ActivityDate","retainedValueId":""},"constants":[],"behavior":"order creation date, else today","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-description","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.taskId","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Description","retainedValueId":""},"constants":[],"behavior":"serialized text","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-command","transferRef":"xfer-response","sources":[],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.commandType","retainedValueId":""},"constants":[{"name":"commandType","value":"completeTask"}],"behavior":"constant completeTask","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-execution","transferRef":"xfer-response","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-execution"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.executionId","retainedValueId":""},"constants":[],"behavior":"echo retained executionId","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-order","transferRef":"xfer-response","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-order"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.orderId","retainedValueId":""},"constants":[],"behavior":"echo retained orderId","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-number","transferRef":"xfer-response","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-number"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.executionNumber","retainedValueId":""},"constants":[],"behavior":"echo retained executionNumber","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-task","transferRef":"xfer-response","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-task"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.taskId","retainedValueId":""},"constants":[],"behavior":"echo retained taskId","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"rule-failure","transferRef":"xfer-response","sources":[{"kind":"STEP_PORT","stepId":"create","port":"FAILURE_OUTCOME","fieldPath":"$.status","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"result","port":"OUTBOUND_REQUEST","fieldPath":"$.error.code","retainedValueId":""},"constants":[{"name":"code","value":"SALESFORCE_TASK_CREATE_ERROR"}],"behavior":"SALESFORCE_TASK_CREATE_ERROR plus the failure text","evidenceRefs":["src-om"]}
        ],"retainedValues":[
          {"existingId":"","alias":"keep-execution","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.executionId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"keep-order","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.orderId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"keep-process","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.processInstanceId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"keep-number","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.executionNumber","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-om"]},
          {"existingId":"","alias":"keep-task","stepRef":"start","source":{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.taskId","retainedValueId":""},"intendedUse":"response","evidenceRefs":["src-om"]}
        ],"question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":"","contradiction":"","defectEvidenceIds":[],"issueCategory":""}
        """;
  }

  private static String priorityRepairCapture() {
    return """
        {"outcome":"PREPARED","requirements":[],"steps":[],"connections":[],"sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"deletes":[],"transfers":[],"rules":[
          {"existingId":"rule-priority","alias":"","transferRef":"xfer-request","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.priority","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Priority","retainedValueId":""},"constants":[],"behavior":"urgent maps to High","evidenceRefs":["src-om"]}
        ],"retainedValues":[],"question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":"","contradiction":"","defectEvidenceIds":[],"issueCategory":""}
        """;
  }

  private static String selection() {
    return """
        {"outcome":"PREPARED","candidateId":"createTask","stepId":"create","catalogId":"model-catalog","method":"DELETE","path":"/model/path","protocol":"grpc","apiKey":"SECRET_SLOT"}
        """;
  }

  private static Path fixture(String name) {
    return fixtureRoot().resolve(name);
  }

  private static Path fixtureRoot() {
    return repoRoot().resolve("ai-service/e2e/product-pipeline/fixtures/work-checkpoints");
  }

  private static Path script() {
    return repoRoot().resolve("ai-service/e2e/product-pipeline/run-work-document-checkpoint.sh");
  }

  private static Path repoRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    if (cursor.getFileName().toString().equals("ai-service")) {
      return cursor.getParent();
    }
    return cursor;
  }
}
