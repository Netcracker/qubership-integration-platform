package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.binding.CatalogResolution;
import org.qubership.integration.platform.ai.plan.workdocument.binding.UnavailableCatalog;
import org.qubership.integration.platform.ai.plan.workdocument.binding.WorkBinding;
import org.qubership.integration.platform.ai.plan.workdocument.flow.WorkLogicalFlow;
import org.qubership.integration.platform.ai.plan.workdocument.mapping.WorkMapping;
import org.qubership.integration.platform.ai.plan.workdocument.recovery.WorkRecovery;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Opt-in checkpoint runner. It calls logical design, operation selection, or supplied mapping and
 * records the model the caller configured. Recovery injects a finding and does not call the model.
 */
public final class WorkCheckpointHarness {

  static final String GATE_MODEL = "gpt-6-luna";

  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");

  private WorkCheckpointHarness() {}

  public static void main(String[] args) throws Exception {
    String checkpoint = arg(args, "--checkpoint");
    String caseId = arg(args, "--case");
    Path report = Path.of(arg(args, "--report"));
    Path fixtures = Path.of(arg(args, "--fixtures"));
    if (!"1".equals(System.getenv("WORK_CHECKPOINT_LIVE"))) {
      write(
          report,
          refused(checkpoint, caseId, "LIVE_NOT_ENABLED", "Set WORK_CHECKPOINT_LIVE=1 to call the configured provider."));
      System.exit(2);
    }
    ArtifactBlobStore publicationStore;
    try {
      publicationStore = openDurableStore(System.getenv("WORK_CHECKPOINT_STORE"));
    } catch (RuntimeException failure) {
      write(
          report,
          failed(checkpoint, caseId, "STORE_UNAVAILABLE", failure.getMessage()));
      System.exit(1);
      return;
    }
    String model = System.getenv("LLM_CHAT_MODEL");
    if (model == null || model.isBlank()) {
      write(
          report,
          failed(
              checkpoint,
              caseId,
              "MISSING_MODEL",
              "LLM_CHAT_MODEL is empty. The harness does not substitute gpt-6-luna."));
      System.exit(1);
    }
    String provider = System.getenv().getOrDefault("LLM_PROVIDER", "auto");
    String apiKey = System.getenv("LLM_API_KEY");
    String baseUrl = System.getenv("LLM_BASE_URL");
    if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
      write(
          report,
          failed(
              checkpoint,
              caseId,
              "MISSING_PROVIDER",
              "LLM_API_KEY and LLM_BASE_URL must already be set. The harness does not change them."));
      System.exit(1);
    }
    boolean binding = "binding".equals(checkpoint);
    WorkTaskModel client =
        request -> {
          ObjectNode schema = JSON.createObjectNode();
          schema.put("name", request.kind().name().toLowerCase());
          schema.set(
              "schema",
              JSON.valueToTree(JsonSchemaElementUtils.toMap(request.responseSchema(), true)));
          return completeChat(baseUrl, apiKey, model, request.prompt(), schema);
        };
    CatalogResolution catalog;
    try {
      catalog = binding ? HostCatalog.open(System.getenv("CATALOG_URL")) : new UnavailableCatalog();
    } catch (RuntimeException failure) {
      write(report, failed(checkpoint, caseId, "CATALOG_CLIENT_UNAVAILABLE", failure.getMessage()));
      System.exit(1);
      return;
    }
    if (binding) {
      HostCatalog.bindConversation("checkpoint-" + caseId);
    }
    int exit;
    try {
      exit =
          run(
              new CheckpointRequest(checkpoint, caseId, report, fixtures, true, publicationStore),
              new CheckpointSession(provider, model, false, client, catalog));
    } finally {
      if (binding) {
        HostCatalog.clearConversation();
      }
    }
    System.exit(exit);
  }

  public static int run(CheckpointRequest request, CheckpointSession session) throws Exception {
    String checkpoint = request.checkpoint();
    if (!List.of("logical", "binding", "mapping", "recovery").contains(checkpoint)) {
      write(
          request.report(),
          failed(checkpoint, request.caseId(), "UNKNOWN_CHECKPOINT", "Checkpoint is not logical, binding, mapping, or recovery."));
      return 2;
    }
    if ("recovery".equals(checkpoint)) {
      if (!request.invokeFacades()) {
        write(
            request.report(),
            refused(checkpoint, request.caseId(), "LIVE_NOT_ENABLED", "Facade invocation is off."));
        return 2;
      }
      return runRecovery(request, session);
    }
    if (!request.invokeFacades()) {
      write(
          request.report(),
          refused(checkpoint, request.caseId(), "LIVE_NOT_ENABLED", "Facade invocation is off."));
      return 2;
    }
    JsonNode spec = caseSpec(request);
    if (spec == null) {
      write(
          request.report(),
          failed(checkpoint, request.caseId(), "UNKNOWN_CASE", "Case is not one of the fixed checkpoint ids."));
      return 2;
    }
    AtomicReference<String> prompt = new AtomicReference<>("");
    AtomicReference<String> response = new AtomicReference<>("");
    AtomicInteger modelCalls = new AtomicInteger();
    WorkTaskModel recording =
        modelRequest -> {
          modelCalls.incrementAndGet();
          prompt.set(modelRequest.prompt());
          String output = session.modelClient().complete(modelRequest);
          response.set(output);
          return output;
        };
    try {
      Published published = invoke(request, spec, session, recording);
      int attempts = Math.max(published.storeAttempts(), modelCalls.get());
      ObjectNode report = base(checkpoint, request.caseId(), spec, session);
      report.put("outcome", published.outcome());
      report.put("documentRevision", published.documentRevision());
      report.put("documentReference", published.documentReference());
      report.put("attempts", attempts);
      report.set("taskScope", published.scope());
      report.put("sanitizedRequest", sanitize(prompt.get()));
      report.put("sanitizedResponse", sanitize(response.get()));
      report.put("resolvedMethod", published.resolvedMethod());
      report.put("resolvedPath", published.resolvedPath());
      report.put("failureCode", "");
      report.put("durable", request.publicationStore() != null);
      write(request.report(), report);
      return passed(request, published) ? 0 : 1;
    } catch (WorkDocumentRejectedException rejected) {
      write(
          request.report(),
          failureReport(checkpoint, request.caseId(), spec, session, rejected.code(), rejected.getMessage(), prompt.get(), response.get()));
      return 1;
    } catch (RuntimeException failure) {
      String code = failureCode(failure);
      write(
          request.report(),
          failureReport(checkpoint, request.caseId(), spec, session, code, String.valueOf(failure.getMessage()), prompt.get(), response.get()));
      return 1;
    }
  }

  private static Published invoke(
      CheckpointRequest request, JsonNode spec, CheckpointSession session, WorkTaskModel model)
      throws Exception {
    boolean durable = request.publicationStore() != null;
    if (durable && request.publicationStore() instanceof InMemoryArtifactBlobStore) {
      throw new IllegalStateException(
          "STORE_UNAVAILABLE: an in-memory map is not a durable document store.");
    }
    ArtifactBlobStore blobs = durable ? request.publicationStore() : new InMemoryArtifactBlobStore();
    Clock clock = durable ? Clock.systemUTC() : Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, JSON, clock);
    WorkDocumentService documents = new WorkDocumentService(runs, artifacts, JSON);
    String runId = "checkpoint-" + request.caseId();
    String stage =
        switch (request.checkpoint()) {
          case "logical" -> "LOGICAL_FLOW";
          case "mapping" -> "DATA_BEHAVIOR";
          default -> "SERVICES";
        };
    runs.create(
        new RunSnapshot(
            runId,
            "checkpoint-" + request.caseId(),
            1L,
            RunStatus.RUNNING,
            stage,
            List.of(new StageSnapshot(stage, StageStatus.RUNNING, List.of(), null)),
            null));
    WorkTaskMaterials materials =
        new WorkTaskMaterials(
            "mapping".equals(request.checkpoint()) ? mappingSchemas() : List.of(),
            List.of("runtime-catalog-only"),
            Map.of("src-om", spec.path("source").asText()));
    WorkCommit commit;
    ObjectNode scope = JSON.createObjectNode();
    if ("mapping".equals(request.checkpoint())) {
      boolean repair = "priority-repair".equals(request.caseId());
      documents.intake(
          runId,
          new WorkDocumentState(
              "pending",
              JSON.readValue(repair ? priorityDocument() : mappingDocument(), ChainWorkDocument.class)),
          "cmd-seed",
          new WorkRepairBudget(3));
      WorkMapping mapping = new WorkMapping(documents, new WorkTaskExecutor(documents, runs, clock));
      if (repair) {
        commit = mapping.repair(runId, "rule-priority", materials, model);
        scope.put("taskId", WorkTaskPlanner.taskId(WorkTaskKind.REPAIR_RULE, "rule-priority"));
      } else {
        commit = mapping.interpret(runId, "xfer-request", materials, model);
        scope.put("taskId", WorkTaskPlanner.taskId(WorkTaskKind.MAP_TRANSFER, "xfer-request"));
      }
      scope.put("skillId", WorkMapping.SKILL_ID);
      scope.put("stage", "DATA_BEHAVIOR");
    } else if ("logical".equals(request.checkpoint())) {
      documents.intake(
          runId,
          new WorkDocumentState("pending", JSON.readValue(emptyDocument(), ChainWorkDocument.class)),
          "cmd-intake",
          new WorkRepairBudget(3));
      WorkLogicalFlow flow = new WorkLogicalFlow(documents, new WorkTaskExecutor(documents, runs, clock));
      commit = flow.design(runId, materials, model);
      scope.put("skillId", WorkLogicalFlow.SKILL_ID);
      scope.put("stage", "LOGICAL_FLOW");
      scope.put(
          "taskId", WorkTaskPlanner.taskId(WorkTaskKind.LOGICAL_DESIGN, "doc-checkpoint"));
    } else {
      documents.intake(
          runId,
          new WorkDocumentState("pending", JSON.readValue(seededDocument(), ChainWorkDocument.class)),
          "cmd-seed",
          new WorkRepairBudget(3));
      WorkBinding binding = new WorkBinding(documents, runs, clock, session.catalog());
      commit = binding.select(runId, "create", materials, model);
      scope.put("skillId", WorkBinding.SKILL_ID);
      scope.put("stage", "SERVICES");
      scope.put("taskId", WorkTaskPlanner.taskId(WorkTaskKind.SELECT_OPERATION, "create"));
    }
    JsonNode stored = stepBinding(commit);
    String reference = "";
    var loaded = runs.load(runId);
    if (loaded.isPresent() && loaded.get().run().workDocumentRef() != null) {
      reference = loaded.get().run().workDocumentRef().artifactId();
    }
    int storeAttempts = loaded.map(document -> document.attempts().size()).orElse(0);
    return new Published(
        commit.outcome().name(),
        commit.documentRevision(),
        reference,
        storeAttempts,
        scope,
        stored.path("method").asText(""),
        stored.path("path").asText(""),
        JSON.valueToTree(commit.state().document()));
  }

  private static boolean passed(CheckpointRequest request, Published published) {
    if (!"PREPARED".equals(published.outcome())) {
      return false;
    }
    if ("om-mapping".equals(request.caseId())) {
      return omMappingReady(published.document());
    }
    return true;
  }

  private static boolean omMappingReady(JsonNode document) {
    List<String> targets = new ArrayList<>();
    List<String> retained = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        retained.add(value.path("source").path("fieldPath").asText());
      }
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          targets.add(rule.path("target").path("fieldPath").asText());
        }
      }
    }
    if (targets.isEmpty() || targets.contains("$.processId")) {
      return false;
    }
    for (String path :
        List.of(
            "$.Subject",
            "$.Priority",
            "$.Status",
            "$.ActivityDate",
            "$.Description",
            "$.commandType",
            "$.executionId",
            "$.orderId",
            "$.executionNumber",
            "$.taskId",
            "$.error.code")) {
      if (!targets.contains(path)) {
        return false;
      }
    }
    for (String path :
        List.of("$.executionId", "$.orderId", "$.processInstanceId", "$.executionNumber", "$.taskId")) {
      if (!retained.contains(path)) {
        return false;
      }
    }
    return true;
  }

  private static JsonNode stepBinding(WorkCommit commit) {
    for (JsonNode step : JSON.valueToTree(commit.state().document()).path("flow").path("steps")) {
      if ("create".equals(step.path("id").asText()) || "createTask".equals(step.path("label").asText())) {
        JsonNode binding = step.path("binding");
        if (binding.isObject()) {
          return binding;
        }
      }
    }
    return JSON.nullNode();
  }

  private static JsonNode caseSpec(CheckpointRequest request) throws Exception {
    String file =
        "mapping".equals(request.checkpoint()) || "recovery".equals(request.checkpoint())
            ? "g2-cases.json"
            : "g1-cases.json";
    JsonNode root = JSON.readTree(request.fixtureRoot().resolve(file).toFile());
    for (JsonNode item : root.path("cases")) {
      if (request.caseId().equals(item.path("id").asText())
          && request.checkpoint().equals(item.path("checkpoint").asText())) {
        return item;
      }
    }
    return null;
  }

  private static ObjectNode base(String checkpoint, String caseId, JsonNode spec, CheckpointSession session) {
    ObjectNode report = JSON.createObjectNode();
    report.put("checkpoint", checkpoint);
    report.put("caseId", caseId);
    report.put("gate", spec == null ? "" : spec.path("gate").asText());
    report.put("effectiveProvider", session.provider());
    report.put("effectiveModel", session.model());
    report.put("gateModel", GATE_MODEL);
    report.put("providerSwitched", session.providerSwitched());
    report.put("materialized", false);
    report.put("durable", false);
    report.put("requiredObservation", spec == null ? "" : spec.path("observation").asText());
    return report;
  }

  private static ObjectNode refused(String checkpoint, String caseId, String code, String message) {
    ObjectNode report = JSON.createObjectNode();
    report.put("checkpoint", checkpoint);
    report.put("caseId", caseId);
    report.put("outcome", "REFUSED");
    report.put("failureCode", code);
    report.put("message", message);
    report.put("materialized", false);
    report.put("providerSwitched", false);
    report.put("gateModel", GATE_MODEL);
    return report;
  }

  private static ObjectNode failed(String checkpoint, String caseId, String code, String message) {
    ObjectNode report = refused(checkpoint, caseId, code, message);
    report.put("outcome", "FAILED");
    return report;
  }

  private static ObjectNode failureReport(
      String checkpoint,
      String caseId,
      JsonNode spec,
      CheckpointSession session,
      String code,
      String message,
      String prompt,
      String response) {
    ObjectNode report = base(checkpoint, caseId, spec, session);
    report.put("outcome", "FAILED");
    report.put("failureCode", code);
    report.put("message", sanitize(message));
    report.put("attempts", 0);
    report.put("sanitizedRequest", sanitize(prompt));
    report.put("sanitizedResponse", sanitize(response));
    report.put("materialized", false);
    return report;
  }

  /**
   * Calls the chat API with the JDK HTTP client. The Quarkus JAX-RS client on this classpath needs
   * CDI, and this main method does not start a container.
   */
  static String completeChat(String baseUrl, String apiKey, String model, String prompt) {
    return completeChat(baseUrl, apiKey, model, prompt, captureSchema());
  }

  private static ObjectNode captureSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("name", "work_task_capture");
    schema.set(
        "schema",
        JSON.valueToTree(JsonSchemaElementUtils.toMap(WorkDocumentCaptureSchema.captureSchema(), true)));
    return schema;
  }

  private static ObjectNode selectionSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("name", "operation_selection");
    ObjectNode body = JSON.createObjectNode();
    body.put("type", "object");
    body.put("additionalProperties", false);
    body.putArray("required").add("outcome").add("candidateId").add("stepId").add("question").add("unresolvedChoice");
    ObjectNode properties = body.putObject("properties");
    ObjectNode outcome = properties.putObject("outcome");
    outcome.put("type", "string");
    outcome.putArray("enum").add("PREPARED").add("NEEDS_CLARIFICATION").add("INPUT_DEFECT");
    properties.putObject("candidateId").put("type", "string");
    properties.putObject("stepId").put("type", "string");
    properties.putObject("question").put("type", "string");
    properties.putObject("unresolvedChoice").put("type", "string");
    schema.set("schema", body);
    return schema;
  }

  static String completeChat(String baseUrl, String apiKey, String model, String prompt, ObjectNode responseSchema) {
    String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    ObjectNode body = JSON.createObjectNode();
    body.put("model", model);
    body.putArray("messages").addObject().put("role", "user").put("content", prompt);
    ObjectNode format = body.putObject("response_format");
    format.put("type", "json_schema");
    ObjectNode jsonSchema = format.putObject("json_schema");
    jsonSchema.put("name", responseSchema.path("name").asText("response"));
    jsonSchema.put("strict", true);
    jsonSchema.set("schema", responseSchema.path("schema"));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(root + "/chat/completions"))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    HttpResponse<String> response;
    try {
      response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Model request was interrupted.", failure);
    } catch (IOException failure) {
      throw new IllegalStateException("Model request failed.", failure);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(modelHttpFailure(response));
    }
    JsonNode tree;
    try {
      tree = JSON.readTree(response.body());
    } catch (Exception failure) {
      throw new IllegalStateException("Model response is not JSON.", failure);
    }
    String content = tree.path("choices").path(0).path("message").path("content").asText("");
    if (content.isBlank()) {
      throw new IllegalStateException("Model response has no message content.");
    }
    return content;
  }

  private static String modelHttpFailure(HttpResponse<String> response) {
    String detail = "";
    try {
      detail = JSON.readTree(response.body()).path("error").path("message").asText("");
    } catch (Exception ignored) {
      detail = "";
    }
    if (detail.isBlank()) {
      return "Model returned HTTP " + response.statusCode() + ".";
    }
    return "Model returned HTTP " + response.statusCode() + ": " + detail;
  }

  public static ArtifactBlobStore openDurableStore(String directory) {
    if (directory == null || directory.isBlank()) {
      throw new IllegalStateException(
          "STORE_UNAVAILABLE: WORK_CHECKPOINT_STORE is not set. Refusing an in-memory document store.");
    }
    return openDurableStore(Path.of(directory));
  }

  public static ArtifactBlobStore openDurableStore(Path directory) {
    return FileCheckpointBlobStore.open(directory);
  }

  private static String failureCode(RuntimeException failure) {
    String message = failure.getMessage() == null ? "" : failure.getMessage();
    if (message.startsWith("CATALOG_CLIENT_UNAVAILABLE")) {
      return "CATALOG_CLIENT_UNAVAILABLE";
    }
    if (message.startsWith("STORE_UNAVAILABLE")) {
      return "STORE_UNAVAILABLE";
    }
    return "CHECKPOINT_FAILED";
  }

  static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value
        .replaceAll("(?<![A-Za-z0-9])sk-[A-Za-z0-9_\\-]+", "[redacted]")
        .replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]+", "Bearer [redacted]")
        .replaceAll("(?i)(\"apiKey\"\\s*:\\s*\")[^\"]*\"", "$1[redacted]\"");
  }

  private static void write(Path report, ObjectNode body) throws Exception {
    if (report.getParent() != null) {
      Files.createDirectories(report.getParent());
    }
    JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), body);
  }

  private static String arg(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (name.equals(args[i])) {
        return args[i + 1];
      }
    }
    throw new IllegalArgumentException("Missing " + name);
  }

  private static String emptyDocument() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-checkpoint",
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
          "progress": {"tasks":[],"findings":[],"questions":[],"approvalReference":"","derivedResultReferences":[],"recheckStages":[]}
        }
        """;
  }

  private static List<SchemaFragment> mappingSchemas() {
    return List.of(
        schema(
            "start",
            "payload",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"subRequestType\":{\"type\":\"string\"},\"orderId\":{\"type\":\"string\"},\"executionId\":{\"type\":\"string\"},\"processInstanceId\":{\"type\":\"string\"},\"executionNumber\":{\"type\":\"string\"},\"taskId\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\"},\"parameters\":{\"type\":\"object\",\"properties\":{\"orderCreationDate\":{\"type\":\"string\"}}}}}"),
        schema(
            "create",
            "request",
            "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"string\"},\"Priority\":{\"type\":\"string\"},\"Status\":{\"type\":\"string\"},\"ActivityDate\":{\"type\":\"string\"},\"Description\":{\"type\":\"string\"}}}"),
        schema("create", "success", "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}"),
        schema(
            "create",
            "failure",
            "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"},\"error\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}}}"),
        schema(
            "result",
            "request",
            "{\"type\":\"object\",\"properties\":{\"commandType\":{\"type\":\"string\"},\"executionId\":{\"type\":\"string\"},\"orderId\":{\"type\":\"string\"},\"processId\":{\"type\":\"string\"},\"executionNumber\":{\"type\":\"string\"},\"taskId\":{\"type\":\"string\"},\"error\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}}}"));
  }

  private static SchemaFragment schema(String stepId, String port, String body) {
    return new SchemaFragment(
        "schema-" + stepId + "-" + port, stepId, port, "hash-" + port, "ref-" + port, body);
  }

  private static String mappingDocument() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-checkpoint-map",
          "sources": [{
            "id": "src-om",
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
              {"id":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the order event","sourceIds":["src-om"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[
                {"id":"keep-execution","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.executionId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-om"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-order","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.orderId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-om"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-process","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.processInstanceId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-om"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-number","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.executionNumber","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-om"],"producerStepId":"start","resolution":"RESOLVED"},
                {"id":"keep-task","source":{"kind":"STEP_PORT","stepId":"start","port":"payload","fieldPath":"$.taskId","retainedValueId":""},"intendedUse":"response","evidenceIds":["src-om"],"producerStepId":"start","resolution":"RESOLVED"}
              ]}},
              {"id":"create","kind":"SERVICE_CALL","label":"Task","intent":"Create the Salesforce task","sourceIds":["src-om"],"requirementIds":[],"binding":{"catalogId":"sys-wfm","version":"2024.4","operationId":"createTask","protocol":"http","method":"POST","path":"/wfm/v1/tasks","contractReferences":["spec-create"],"exposedPorts":["payload","request","success","failure"]},"data":{"transfers":[{"id":"xfer-request","sourcePorts":[{"stepId":"start","portName":"payload"}],"targetPort":{"stepId":"create","portName":"request"},"requirementIds":[],"rules":[],"decision":"UNSPECIFIED","outcome":"UNSPECIFIED","requiredRetainedIds":[]}],"retainedValues":[]}},
              {"id":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the outcome","sourceIds":["src-om"],"requirementIds":[],"binding":null,"data":{"transfers":[{"id":"xfer-reply","sourcePorts":[{"stepId":"create","portName":"success"},{"stepId":"create","portName":"failure"}],"targetPort":{"stepId":"result","portName":"request"},"requirementIds":[],"requiredRetainedIds":["keep-execution","keep-order","keep-number","keep-task"],"decision":"UNSPECIFIED","outcome":"UNSPECIFIED","rules":[
                {"id":"rule-command","sources":[],"target":{"kind":"STEP_PORT","stepId":"result","port":"request","fieldPath":"$.commandType","retainedValueId":""},"constants":[{"name":"commandType","value":"completeTask"}],"behavior":"constant completeTask","evidenceIds":["src-om"]},
                {"id":"rule-execution","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-execution"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"request","fieldPath":"$.executionId","retainedValueId":""},"constants":[],"behavior":"echo retained executionId","evidenceIds":["src-om"]},
                {"id":"rule-order","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-order"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"request","fieldPath":"$.orderId","retainedValueId":""},"constants":[],"behavior":"echo retained orderId","evidenceIds":["src-om"]},
                {"id":"rule-number","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-number"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"request","fieldPath":"$.executionNumber","retainedValueId":""},"constants":[],"behavior":"echo retained executionNumber","evidenceIds":["src-om"]},
                {"id":"rule-task","sources":[{"kind":"RETAINED","stepId":"","port":null,"fieldPath":"","retainedValueId":"keep-task"}],"target":{"kind":"STEP_PORT","stepId":"result","port":"request","fieldPath":"$.taskId","retainedValueId":""},"constants":[],"behavior":"echo retained taskId","evidenceIds":["src-om"]},
                {"id":"rule-failure","sources":[{"kind":"STEP_PORT","stepId":"create","port":"failure","fieldPath":"$.status","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"result","port":"request","fieldPath":"$.error.code","retainedValueId":""},"constants":[{"name":"code","value":"SALESFORCE_TASK_CREATE_ERROR"}],"behavior":"SALESFORCE_TASK_CREATE_ERROR plus the failure text","evidenceIds":["src-om"]}
              ]}],"retainedValues":[]}}
            ],
            "connections": [],
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

  private static String priorityDocument() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-checkpoint-repair",
          "sources": [{
            "id": "src-om",
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
              {"id":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the order event","sourceIds":["src-om"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}},
              {"id":"create","kind":"SERVICE_CALL","label":"Task","intent":"Create the Salesforce task","sourceIds":["src-om"],"requirementIds":[],"binding":{"catalogId":"sys-wfm","version":"2024.4","operationId":"createTask","protocol":"http","method":"POST","path":"/wfm/v1/tasks","contractReferences":["spec-create"],"exposedPorts":["payload","request","success","failure"]},"data":{"transfers":[{
                "id":"xfer-request",
                "sourcePorts":[{"stepId":"start","portName":"payload"}],
                "targetPort":{"stepId":"create","portName":"request"},
                "requirementIds":[],
                "rules":[
                  {"id":"rule-subject","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.name","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Subject","retainedValueId":""},"constants":[],"behavior":"name or fallback","evidenceIds":["src-om"]},
                  {"id":"rule-priority","sources":[{"kind":"STEP_PORT","stepId":"start","port":"INBOUND_PAYLOAD","fieldPath":"$.priority","retainedValueId":""}],"target":{"kind":"STEP_PORT","stepId":"create","port":"OUTBOUND_REQUEST","fieldPath":"$.Priority","retainedValueId":""},"constants":[],"behavior":"high to High","evidenceIds":["src-om"]}
                ],
                "decision":"UNSPECIFIED"
              }],"retainedValues":[]}},
              {"id":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the outcome","sourceIds":["src-om"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}}
            ],
            "connections": [],
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

  private static String seededDocument() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-checkpoint-bind",
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
            "steps": [
              {"id":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceIds":["src-om"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}}
            ],
            "connections": [],
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

  private static int runRecovery(CheckpointRequest request, CheckpointSession session) throws Exception {
    JsonNode spec = caseSpec(request);
    if (spec == null) {
      write(
          request.report(),
          failed(
              request.checkpoint(),
              request.caseId(),
              "UNKNOWN_CASE",
              "Case is not one of the fixed checkpoint ids."));
      return 2;
    }
    try {
      RecoveryRun routed = executeRecovery(request);
      ObjectNode report = base(request.checkpoint(), request.caseId(), spec, session);
      report.put("outcome", routed.ok() ? "PREPARED" : "FAILED");
      report.put("documentRevision", routed.documentRevision());
      report.put("attempts", routed.correctiveCharges());
      report.put("failureCode", routed.ok() ? "" : "RECOVERY_OBSERVATION");
      report.put("ownerStage", routed.ownerStage());
      report.put("causeKey", routed.causeKey());
      report.put("repairsRemaining", routed.repairsRemaining());
      report.put("blocked", routed.blocked());
      report.put("rulesPreserved", routed.rulesPreserved());
      report.set("recheckStages", JSON.valueToTree(routed.recheckStages()));
      report.put("modelCalls", 0);
      report.put("sanitizedRequest", "");
      report.put("sanitizedResponse", "");
      report.put("durable", request.publicationStore() != null);
      write(request.report(), report);
      return routed.ok() ? 0 : 1;
    } catch (WorkDocumentRejectedException rejected) {
      write(
          request.report(),
          failureReport(
              request.checkpoint(),
              request.caseId(),
              spec,
              session,
              rejected.code(),
              rejected.getMessage(),
              "",
              ""));
      return 1;
    } catch (RuntimeException failure) {
      write(
          request.report(),
          failureReport(
              request.checkpoint(),
              request.caseId(),
              spec,
              session,
              failureCode(failure),
              String.valueOf(failure.getMessage()),
              "",
              ""));
      return 1;
    }
  }

  private static RecoveryRun executeRecovery(CheckpointRequest request) throws Exception {
    boolean durable = request.publicationStore() != null;
    if (durable && request.publicationStore() instanceof InMemoryArtifactBlobStore) {
      throw new IllegalStateException(
          "STORE_UNAVAILABLE: an in-memory map is not a durable document store.");
    }
    ArtifactBlobStore blobs = durable ? request.publicationStore() : new InMemoryArtifactBlobStore();
    Clock clock = durable ? Clock.systemUTC() : Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, JSON, clock);
    WorkDocumentService documents = new WorkDocumentService(runs, artifacts, JSON);
    String runId = "checkpoint-" + request.caseId();
    runs.create(
        new RunSnapshot(
            runId,
            "checkpoint-" + request.caseId(),
            1L,
            RunStatus.RUNNING,
            "DATA_BEHAVIOR",
            List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        runId,
        new WorkDocumentState("pending", JSON.readValue(recoveryDocument(), ChainWorkDocument.class)),
        "cmd-seed",
        new WorkRepairBudget(3));
    WorkRecovery recovery = WorkRecovery.create(documents, runs);
    return switch (request.caseId()) {
      case "wrong-binding-recovery" -> wrongBinding(documents, runs, recovery, runId);
      case "same-cause-limit" -> sameCause(documents, runs, recovery, runId);
      case "restart-recovery" -> restartRecovery(documents, runs, recovery, runId);
      default -> throw new IllegalArgumentException("Case is not one of the fixed checkpoint ids.");
    };
  }

  private static RecoveryRun wrongBinding(
      WorkDocumentService documents, ProductPipelineRunStore runs, WorkRecovery recovery, String runId) {
    WorkRecovery.Result routed =
        recovery.route(
            runId,
            WorkRecovery.Defect.of(
                "create",
                "WRONG_OPERATION",
                "binding",
                "Mapping found the wrong operation.",
                "src-om"),
            "cmd-wrong-binding");
    List<String> recheck = recheckStages(documents, runId);
    boolean rules = rulesPreserved(documents, runId);
    boolean ok =
        routed.owner() == WorkStage.SERVICES
            && routed.dispatched()
            && recheck.contains("DATA_BEHAVIOR")
            && !recheck.contains("SERVICES")
            && rules;
    return new RecoveryRun(
        ok,
        documents.read(runId).revision(),
        correctiveCharges(runs, runId),
        routed.owner().name(),
        routed.causeKey(),
        routed.repairsRemaining(),
        false,
        rules,
        recheck);
  }

  private static RecoveryRun sameCause(
      WorkDocumentService documents, ProductPipelineRunStore runs, WorkRecovery recovery, String runId) {
    WorkRecovery.Result mapping =
        recovery.route(
            runId,
            WorkRecovery.Defect.of(
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "Priority targets the wrong operation.",
                "src-om"),
            "cmd-map");
    WorkRecovery.Result binding =
        recovery.route(
            runId,
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
            runId,
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
    WorkRecovery.Result blocked =
        recovery.route(
            runId,
            new WorkRecovery.Defect(
                mapping.findingId(),
                "rule-priority",
                "WRONG_OPERATION",
                "behavior",
                "Another wording for the same defect.",
                List.of("src-om"),
                "",
                ""),
            "cmd-fourth");
    int charges = correctiveCharges(runs, runId);
    boolean rules = rulesPreserved(documents, runId);
    boolean ok =
        mapping.causeKey().equals(binding.causeKey())
            && mapping.causeKey().equals(logical.causeKey())
            && mapping.causeKey().equals(blocked.causeKey())
            && charges == 3
            && blocked.exhausted()
            && !blocked.dispatched()
            && rules;
    return new RecoveryRun(
        ok,
        documents.read(runId).revision(),
        charges,
        logical.owner().name(),
        blocked.causeKey(),
        blocked.repairsRemaining(),
        blocked.exhausted(),
        rules,
        recheckStages(documents, runId));
  }

  private static RecoveryRun restartRecovery(
      WorkDocumentService documents, ProductPipelineRunStore runs, WorkRecovery recovery, String runId) {
    WorkRecovery.Result first =
        recovery.route(
            runId,
            WorkRecovery.Defect.of(
                "rule-priority", "WRONG_OPERATION", "behavior", "Priority is wrong.", "src-om"),
            "cmd-once");
    WorkRecovery restarted = WorkRecovery.create(documents, runs);
    String causeKey = restarted.causeKey(runId, first.findingId());
    int remaining = restarted.repairsRemaining(runId, causeKey);
    boolean ok = first.causeKey().equals(causeKey) && remaining == first.repairsRemaining() && remaining == 2;
    return new RecoveryRun(
        ok,
        documents.read(runId).revision(),
        correctiveCharges(runs, runId),
        first.owner().name(),
        causeKey,
        remaining,
        false,
        rulesPreserved(documents, runId),
        recheckStages(documents, runId));
  }

  private static List<String> recheckStages(WorkDocumentService documents, String runId) {
    List<String> stages = new ArrayList<>();
    for (JsonNode stage : JSON.valueToTree(documents.read(runId).document()).path("progress").path("recheckStages")) {
      stages.add(stage.asText());
    }
    return stages;
  }

  private static boolean rulesPreserved(WorkDocumentService documents, String runId) {
    List<String> ids = new ArrayList<>();
    for (JsonNode step : JSON.valueToTree(documents.read(runId).document()).path("flow").path("steps")) {
      if (!"create".equals(step.path("id").asText())) {
        continue;
      }
      for (JsonNode rule : step.path("data").path("transfers").get(0).path("rules")) {
        ids.add(rule.path("id").asText());
      }
    }
    return ids.equals(List.of("rule-subject", "rule-priority"));
  }

  private static int correctiveCharges(ProductPipelineRunStore runs, String runId) {
    return (int)
        runs.load(runId).orElseThrow().transitions().stream()
            .filter(
                transition ->
                    transition.reason() != null
                        && transition.reason()
                            .startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX))
            .count();
  }

  private static String recoveryDocument() {
    return """
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

  private record RecoveryRun(
      boolean ok,
      String documentRevision,
      int correctiveCharges,
      String ownerStage,
      String causeKey,
      int repairsRemaining,
      boolean blocked,
      boolean rulesPreserved,
      List<String> recheckStages) {}

  private record Published(
      String outcome,
      String documentRevision,
      String documentReference,
      int storeAttempts,
      ObjectNode scope,
      String resolvedMethod,
      String resolvedPath,
      JsonNode document) {}
}
