package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.binding.UnavailableCatalog;
import org.qubership.integration.platform.ai.plan.workdocument.binding.WorkBinding;
import org.qubership.integration.platform.ai.plan.workdocument.flow.WorkLogicalFlow;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Opt-in checkpoint runner. It calls {@link WorkLogicalFlow} or {@link WorkBinding} and records the
 * model the caller configured. Mapping and recovery are not implemented and fail closed.
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
    WorkTaskModel client =
        prompt ->
            OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .build()
                .chat(prompt);
    int exit =
        run(
            new CheckpointRequest(checkpoint, caseId, report, fixtures, true, publicationStore),
            new CheckpointSession(provider, model, false, client, new UnavailableCatalog()));
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
    if ("mapping".equals(checkpoint) || "recovery".equals(checkpoint)) {
      write(
          request.report(),
          failed(
              checkpoint,
              request.caseId(),
              "MISSING_CAPABILITY",
              "The " + checkpoint + " capability is not implemented. The harness does not report success."));
      return 1;
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
          failed(checkpoint, request.caseId(), "UNKNOWN_CASE", "Case is not one of the fixed G1 ids."));
      return 2;
    }
    AtomicReference<String> prompt = new AtomicReference<>("");
    AtomicReference<String> response = new AtomicReference<>("");
    AtomicInteger modelCalls = new AtomicInteger();
    WorkTaskModel recording =
        text -> {
          modelCalls.incrementAndGet();
          prompt.set(text);
          String output = session.modelClient().complete(text);
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
      return "PREPARED".equals(published.outcome()) ? 0 : 1;
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
    String stage = "logical".equals(request.checkpoint()) ? "LOGICAL_FLOW" : "SERVICES";
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
            List.of(),
            List.of("runtime-catalog-only"),
            Map.of("src-om", spec.path("source").asText()));
    WorkCommit commit;
    ObjectNode scope = JSON.createObjectNode();
    if ("logical".equals(request.checkpoint())) {
      documents.intake(
          runId,
          new WorkDocumentState("pending", JSON.readValue(emptyDocument(), ChainWorkDocument.class)),
          "cmd-intake",
          new WorkRepairBudget(3));
      WorkLogicalFlow flow = new WorkLogicalFlow(documents, new WorkTaskExecutor(documents, runs, clock));
      commit = flow.design(runId, materials, model);
      scope.put("skillId", WorkLogicalFlow.SKILL_ID);
      scope.put("stage", "LOGICAL_FLOW");
      scope.put("taskId", "logical-design");
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
      scope.put("taskId", "operation-selection");
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
        stored.path("path").asText(""));
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
    JsonNode root = JSON.readTree(request.fixtureRoot().resolve("g1-cases.json").toFile());
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
        .replaceAll("sk-[A-Za-z0-9_\\-]+", "[redacted]")
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
          "schemaVersion": 1,
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

  private static String seededDocument() {
    return """
        {
          "schemaVersion": 1,
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

  private record Published(
      String outcome,
      String documentRevision,
      String documentReference,
      int storeAttempts,
      ObjectNode scope,
      String resolvedMethod,
      String resolvedPath) {}
}
