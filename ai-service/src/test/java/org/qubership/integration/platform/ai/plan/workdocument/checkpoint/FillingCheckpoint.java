package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.FillingCheckpointIntake;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner;
import org.qubership.integration.platform.ai.plan.workdocument.FillingResult;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentFilling;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.binding.CatalogResolution;
import org.qubership.integration.platform.ai.plan.workdocument.binding.OfflineCatalog;
import org.qubership.integration.platform.ai.plan.workdocument.recovery.WorkRecovery;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Drives {@link WorkDocumentFilling} for one checkpoint process. It advances, accepts one answer
 * file, and writes the report. It does not choose handlers or repair the document.
 */
final class FillingCheckpoint {

  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private static final AtomicReference<String> OBSERVED_MODEL = new AtomicReference<>();
  private static final List<String> CASES =
      List.of(
          "om-progressive",
          "om-controlled-recovery",
          "om-upstream-recovery",
          "om-semantic-recovery");

  private FillingCheckpoint() {}

  static int execute(WorkCheckpointHarness.CliArgs args) throws Exception {
    OBSERVED_MODEL.set(null);
    String code = validate(args);
    if (code != null) {
      writeInvalid(args, code);
      return 2;
    }
    int limit = 40;
    if (args.maxModelCalls != null) {
      try {
        limit = Integer.parseInt(args.maxModelCalls);
      } catch (NumberFormatException failure) {
        writeInvalid(args, "INVALID_ARGUMENT");
        return 2;
      }
      if (limit <= 0) {
        writeInvalid(args, "INVALID_ARGUMENT");
        return 2;
      }
    }
    boolean live = "1".equals(System.getenv("WORK_CHECKPOINT_LIVE"));
    ArtifactBlobStore store;
    try {
      store = WorkCheckpointHarness.openDurableStore(System.getenv("WORK_CHECKPOINT_STORE"));
    } catch (RuntimeException failure) {
      write(
          args.report,
          failed(args.checkpoint, args.caseId, "STORE_UNAVAILABLE", failure.getMessage()));
      return 1;
    }
    CheckpointSession session;
    boolean sequentialFake;
    if (!live) {
      String provider = System.getenv().getOrDefault("LLM_PROVIDER", "offline");
      String model = System.getenv("LLM_CHAT_MODEL");
      if (model == null || model.isBlank()) {
        model = "fake-sequential";
      }
      sequentialFake = true;
      session =
          new CheckpointSession(
              provider,
              model,
              false,
              request -> {
                throw new IllegalStateException("Offline filling does not call the session model.");
              },
              new OfflineCatalog());
    } else {
      String model = System.getenv("LLM_CHAT_MODEL");
      String apiKey = System.getenv("LLM_API_KEY");
      String baseUrl = System.getenv("LLM_BASE_URL");
      if (model == null || model.isBlank() || apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
        write(
            args.report,
            failed(
                args.checkpoint,
                args.caseId,
                "MISSING_PROVIDER",
                "LLM_CHAT_MODEL, LLM_API_KEY, and LLM_BASE_URL must already be set."));
        return 1;
      }
      CatalogResolution catalog;
      try {
        catalog = HostCatalog.open(System.getenv("CATALOG_URL"));
      } catch (RuntimeException failure) {
        write(args.report, failed(args.checkpoint, args.caseId, "CATALOG_CLIENT_UNAVAILABLE", failure.getMessage()));
        return 1;
      }
      HostCatalog.bindConversation("filling-" + args.runId);
      sequentialFake = false;
      String provider = System.getenv().getOrDefault("LLM_PROVIDER", "auto");
      session =
          new CheckpointSession(
              provider,
              model,
              false,
              request -> {
                ObjectNode schema = JSON.createObjectNode();
                schema.put("name", request.kind().name().toLowerCase());
                schema.set(
                    "schema",
                    JSON.valueToTree(JsonSchemaElementUtils.toMap(request.responseSchema(), true)));
                return WorkCheckpointHarness.completeChat(
                    baseUrl, apiKey, model, request.prompt(), schema, OBSERVED_MODEL);
              },
              catalog);
    }
    try {
      return run(
          new CheckpointRequest(
              args.checkpoint,
              args.caseId,
              args.report,
              args.fixtures,
              true,
              store,
              args.runId,
              args.resume,
              args.inputFile,
              limit,
              null,
              sequentialFake),
          session);
    } finally {
      if (live) {
        HostCatalog.clearConversation();
      }
    }
  }

  static int run(CheckpointRequest request, CheckpointSession session) throws Exception {
    OBSERVED_MODEL.set(null);
    if (!request.invokeFacades()) {
      write(
          request.report(),
          refused(request.checkpoint(), request.caseId(), "LIVE_NOT_ENABLED", "Facade invocation is off."));
      return 2;
    }
    JsonNode spec = caseSpec(request);
    if (spec == null) {
      write(
          request.report(),
          failed(request.checkpoint(), request.caseId(), "UNKNOWN_CASE", "Case is not a filling checkpoint id."));
      return 2;
    }
    String invalid = validateRequest(request);
    if (invalid != null) {
      write(request.report(), failed(request.checkpoint(), request.caseId(), invalid, invalidMessage(invalid)));
      return 2;
    }
    if (request.publicationStore() == null || request.publicationStore() instanceof InMemoryArtifactBlobStore) {
      write(
          request.report(),
          failed(
              request.checkpoint(),
              request.caseId(),
              "STORE_UNAVAILABLE",
              "Filling requires a durable document store."));
      return 1;
    }
    int limit = request.maxModelCalls() > 0 ? request.maxModelCalls() : 40;
    Clock clock = Clock.systemUTC();
    CompilationArtifacts artifacts = new CompilationArtifacts(request.publicationStore(), JSON, clock);
    ProductPipelineRunStore runs = new ProductPipelineRunStore(request.publicationStore(), JSON, clock);
    WorkDocumentService documents = new WorkDocumentService(runs, artifacts, JSON);
    String runId = request.runId();
    try {
      boolean exists = runs.load(runId).isPresent();
      if (!request.resume() && exists) {
        write(
            request.report(),
            failed(request.checkpoint(), request.caseId(), "RUN_EXISTS", "The filling run is already in the store. Pass --resume to continue it."));
        return 2;
      }
      if (request.resume() && !exists) {
        write(
            request.report(),
            failed(request.checkpoint(), request.caseId(), "RUN_NOT_FOUND", "The filling run is not in the store. Start it without --resume."));
        return 2;
      }
      if (!request.resume()) {
        intake(documents, runs, runId, session.model());
      }
      Instant deadline =
          request.deadline() != null
              ? request.deadline()
              : clock.instant().plus(WorkCheckpointHarness.INVOCATION_DEADLINE);
      Cursor cursor = readCursor(request.publicationStore(), runId);
      Fault fault = readFault(request.publicationStore(), runId, request.caseId());
      List<Invocation> invocations = readInvocations(request.publicationStore(), runId);
      CatalogResolution catalog = request.sequentialFake() ? new OfflineCatalog() : session.catalog();
      SequentialFillingModel fake =
          new SequentialFillingModel(
              () -> JSON.valueToTree(documents.read(runId).document()),
              "om-upstream-recovery".equals(request.caseId()),
              fault.reportedMissing);
      WorkTaskModel delegate = request.sequentialFake() ? fake : session.modelClient();
      WorkTaskModel transport =
          task -> {
            ChainWorkDocument current = documents.read(runId).document();
            String baseRevision = documents.read(runId).revision();
            String actual = delegate.complete(task);
            String validation = fault.apply(task.kind(), actual, JSON.valueToTree(documents.read(runId).document()));
            if (actual.contains("MISSING_RETAINED")) {
              fault.reportedMissing = true;
            }
            invocations.add(
                Invocation.of(
                    task,
                    actual,
                    validation,
                    cursor,
                    plannedTask(current, task.taskKey()),
                    JSON.valueToTree(current),
                    baseRevision));
            if (task.kind() == WorkTaskKind.MAP_TRANSFER || task.kind() == WorkTaskKind.REPAIR_RULE) {
              cursor.mappingModelCalls++;
              if (ownsOneTransfer(documents, runId, task)) {
                cursor.mappingOneTransfer++;
              }
            }
            cursor.modelCalls++;
            if (!fault.detectionMechanism.isBlank()
                && fault.actualRepairKind.equals(task.kind().name())) {
              fault.correctiveModelCalls++;
              if (task.kind() == WorkTaskKind.DEFINE_TRANSFERS) {
                fault.downstreamReturn = true;
              }
            }
            try {
              saveInvocations(request.publicationStore(), runId, invocations);
              saveCursor(request.publicationStore(), runId, cursor);
              saveFault(request.publicationStore(), runId, fault);
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
            return validation;
          };
      WorkDocumentFilling filling =
          new WorkDocumentFilling(
              documents,
              runs,
              artifacts,
              transport,
              catalog,
              clock,
              WorkRecovery.create(documents, runs),
              new RetryPolicy(3, 250L));
      boolean appendTrace = request.resume();
      if (clock.instant().isAfter(deadline)) {
        return finish(request, session, spec, documents, runs, cursor, fault, invocations, null, "DEADLINE", appendTrace);
      }
      if (!cursor.pending.isBlank()) {
        FillingResult replayed = filling.advance(runId, cursor.pending);
        String played = cursor.pending;
        cursor.pending = "";
        saveCursor(request.publicationStore(), runId, cursor);
        note(fault, replayed);
        applyRoute(fault, JSON.valueToTree(documents.read(runId).document()));
        emit(request, documents, runs, runId, played, replayed, invocations, fault, appendTrace);
        appendTrace = true;
        saveFault(request.publicationStore(), runId, fault);
        if (request.inputFile() == null && terminal(replayed)) {
          return finish(request, session, spec, documents, runs, cursor, fault, invocations, replayed, "", appendTrace);
        }
      }
      if (request.inputFile() != null) {
        if (clock.instant().isAfter(deadline)) {
          return finish(request, session, spec, documents, runs, cursor, fault, invocations, null, "DEADLINE", appendTrace);
        }
        JsonNode input = JSON.readTree(request.inputFile().toFile());
        String inputId = input.path("inputId").asText();
        String questionId = input.path("questionId").asText();
        String text = input.path("text").asText();
        if (inputId.isBlank() || questionId.isBlank() || text.isBlank()) {
          return finish(request, session, spec, documents, runs, cursor, fault, invocations, null, "INVALID_INPUT", appendTrace);
        }
        if (!questionOpen(documents, runId, questionId)) {
          return finish(request, session, spec, documents, runs, cursor, fault, invocations, null, "QUESTION_NOT_OPEN", appendTrace);
        }
        filling.acceptInput(runId, questionId, inputId, text);
        emitAnswer(request, questionId, inputId, appendTrace);
        appendTrace = true;
      }
      FillingResult last = null;
      int steps = 0;
      int bound = Math.max(limit, 8) * 4;
      while (steps < bound) {
        if (clock.instant().isAfter(deadline)) {
          return finish(request, session, spec, documents, runs, cursor, fault, invocations, last, "DEADLINE", appendTrace);
        }
        ProductPipelineRunDocument loaded = runs.load(runId).orElseThrow();
        if (runs.providerDeliveryReservations(loaded).size() >= limit) {
          return finish(request, session, spec, documents, runs, cursor, fault, invocations, last, "MODEL_CALL_LIMIT", appendTrace);
        }
        steps++;
        String commandId = cursor.pending.isBlank() ? "advance-" + cursor.next : cursor.pending;
        if (cursor.pending.isBlank()) {
          cursor.next++;
        }
        cursor.pending = commandId;
        saveCursor(request.publicationStore(), runId, cursor);
        last = filling.advance(runId, commandId);
        cursor.pending = "";
        saveCursor(request.publicationStore(), runId, cursor);
        note(fault, last);
        applyRoute(fault, JSON.valueToTree(documents.read(runId).document()));
        emit(request, documents, runs, runId, commandId, last, invocations, fault, appendTrace);
        appendTrace = true;
        saveFault(request.publicationStore(), runId, fault);
        if (blockingFailure(documents, runId)) {
          return finish(request, session, spec, documents, runs, cursor, fault, invocations, last, "FIELD_ASSERTION", appendTrace);
        }
        if (last.action() == FillingResult.Action.RETRY_CURRENT) {
          long delay = last.retryDelayMs();
          if (delay > 5_000L) {
            return finish(request, session, spec, documents, runs, cursor, fault, invocations, last, "RETRY_DELAY", appendTrace);
          }
          if (delay > 0L) {
            Thread.sleep(delay);
          }
          continue;
        }
        if (last.action() != FillingResult.Action.ADVANCED) {
          break;
        }
      }
      if (last == null || last.action() == FillingResult.Action.ADVANCED || last.action() == FillingResult.Action.RETRY_CURRENT) {
        return finish(request, session, spec, documents, runs, cursor, fault, invocations, last, "ADVANCE_BOUND", appendTrace);
      }
      return finish(request, session, spec, documents, runs, cursor, fault, invocations, last, "", appendTrace);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return finish(request, session, spec, documents, runs, readCursor(request.publicationStore(), runId), readFault(request.publicationStore(), runId, request.caseId()), List.of(), null, "INTERRUPTED", request.resume());
    } catch (RuntimeException failure) {
      String code = failure.getMessage() == null ? "CHECKPOINT_FAILED" : failure.getClass().getSimpleName();
      if (failure.getMessage() != null && failure.getMessage().startsWith("STORE_UNAVAILABLE")) {
        code = "STORE_UNAVAILABLE";
      }
      try {
        return finish(request, session, spec, documents, runs, readCursor(request.publicationStore(), runId), readFault(request.publicationStore(), runId, request.caseId()), List.of(), null, code, request.resume());
      } catch (RuntimeException nested) {
        write(request.report(), failed(request.checkpoint(), request.caseId(), code, String.valueOf(failure.getMessage())));
        return 1;
      }
    }
  }

  private static void intake(WorkDocumentService documents, ProductPipelineRunStore runs, String runId, String model)
      throws Exception {
    runs.create(
        new RunSnapshot(
            runId,
            "filling-" + runId,
            1L,
            RunStatus.RUNNING,
            "LOGICAL_FLOW",
            List.of(new StageSnapshot("LOGICAL_FLOW", StageStatus.RUNNING, List.of(), null)),
            null));
    String raw = Files.readString(canonicalSource());
    String text = canonicalBlock(raw).replace("<effective-model-label>", modelLabel(model)).replace("<run-id>", runId);
    String hash = sha256(text);
    WorkDocumentState state =
        FillingCheckpointIntake.sourceState("doc-" + runId, "src-om", text, hash, "om-salesforce.md");
    documents.intake(runId, documents.indexSourcePassages(state), "cmd-intake", new WorkRepairBudget(3));
  }

  private static int finish(
      CheckpointRequest request,
      CheckpointSession session,
      JsonNode spec,
      WorkDocumentService documents,
      ProductPipelineRunStore runs,
      Cursor cursor,
      Fault fault,
      List<Invocation> invocations,
      FillingResult last,
      String failureCode,
      boolean appendTrace)
      throws Exception {
    List<Invocation> storedInvocations = readInvocations(request.publicationStore(), request.runId());
    if (storedInvocations.size() > invocations.size()) {
      invocations = storedInvocations;
    }
    JsonNode document = reload(request, documents);
    ProductPipelineRunDocument loaded = runs.load(request.runId()).orElse(null);
    String action = last == null ? "" : last.action().name();
    if ("READY_FOR_PRESENTATION".equals(action) && readyGap(document, cursor, request) != null) {
      failureCode = "FIELD_ASSERTION";
    }
    String verdict = verdict(request.caseId(), fault);
    boolean faultCase = faultCase(request.caseId());
    if (faultCase && !"detected-and-repaired".equals(verdict) && !"routed-to-outline-owner".equals(verdict) && !"early-prevention".equals(verdict)) {
      if (failureCode.isBlank()) {
        failureCode = "om-semantic-recovery".equals(request.caseId()) ? "UNDETECTED_SEMANTIC" : "FAULT_CASE";
      }
    }
    if ("om-semantic-recovery".equals(request.caseId()) && "undetected".equals(verdict)) {
      failureCode = "UNDETECTED_SEMANTIC";
    }
    ObjectNode report = base(request, session, spec);
    String outcome = failureCode.isBlank() ? action : ("FIELD_ASSERTION".equals(failureCode) || "UNDETECTED_SEMANTIC".equals(failureCode) ? action : "FAILED");
    if (outcome.isBlank()) {
      outcome = "FAILED";
    }
    report.put("outcome", outcome);
    report.put("failureCode", failureCode);
    report.put("exitReason", failureCode.isBlank() ? action : failureCode);
    report.put("runId", request.runId());
    report.put("buildIdentity", blankToEmpty(System.getenv("WORK_CHECKPOINT_BUILD")));
    String content = document.path("sources").path(0).path("content").asText("");
    report.put("sourceHash", sha256(content));
    report.put("canonicalFileHash", sha256(Files.readString(canonicalSource())));
    report.put("effectiveProvider", session.provider());
    report.put("effectiveModel", session.model());
    String observed = request.sequentialFake() ? null : OBSERVED_MODEL.get();
    if (observed == null || observed.isBlank()) {
      report.putNull("responseModel");
    } else {
      report.put("responseModel", observed);
    }
    report.put("providerSwitched", session.providerSwitched());
    report.put("modelCalls", cursor.modelCalls);
    report.put("httpCalls", cursor.modelCalls);
    int reservations = loaded == null ? 0 : runs.providerDeliveryReservations(loaded).size();
    int confirmed = loaded == null ? 0 : runs.confirmedProviderDeliveries(loaded).size();
    int uncertain = loaded == null ? 0 : runs.uncertainProviderDeliveries(loaded).size();
    report.put("providerDeliveryReservations", reservations);
    report.put("confirmedCalls", confirmed);
    report.put("uncertainProviderAttempts", uncertain);
    report.put("attempts", loaded == null ? 0 : loaded.attempts().size());
    report.put("durable", true);
    report.put("materialized", false);
    String reference = "";
    if (loaded != null && loaded.run().workDocumentRef() != null) {
      reference = loaded.run().workDocumentRef().artifactId();
    }
    String revision = last == null ? "" : last.documentRevision();
    if (revision.isBlank()) {
      revision = documents.read(request.runId()).revision();
    }
    report.put("documentRevision", revision);
    report.put("documentReference", reference);
    report.put("mappingModelCalls", cursor.mappingModelCalls);
    report.put("mappingCallsWithOneTransfer", cursor.mappingOneTransfer);
    ArrayNode questions = questions(document);
    report.set("questions", questions);
    report.put("openQuestions", questions.size());
    report.put("pendingTasks", pending(document));
    report.put("uncoveredRequirements", uncovered(document));
    report.put("syntheticSchemaCount", syntheticSchemas(document));
    report.set("caseBindings", caseBindings(document));
    report.set("requirementChecklist", checklist(document));
    Invocation latest = invocations.isEmpty() ? null : invocations.get(invocations.size() - 1);
    report.put("sanitizedRequest", latest == null ? "" : WorkCheckpointHarness.sanitize(latest.prompt));
    ArrayNode invocationNodes = report.putArray("invocations");
    Path artifacts = request.report().resolveSibling("artifacts");
    Files.createDirectories(artifacts);
    int index = 1;
    for (Invocation invocation : invocations) {
      String name = "call-" + index;
      Path requestFile = artifacts.resolve(name + "-request.txt");
      Path responseFile = artifacts.resolve(name + "-model-response.txt");
      Path validationFile = artifacts.resolve(name + "-validation-input.txt");
      Path schemaFile = artifacts.resolve(name + "-schema.json");
      Files.writeString(requestFile, WorkCheckpointHarness.sanitize(invocation.prompt));
      Files.writeString(responseFile, WorkCheckpointHarness.sanitize(invocation.actualOutput));
      Files.writeString(validationFile, WorkCheckpointHarness.sanitize(invocation.validationInput));
      Files.writeString(schemaFile, invocation.schemaJson);
      ObjectNode node = invocationNodes.addObject();
      node.put("commandId", invocation.commandId);
      node.put("taskId", invocation.taskId);
      node.put("taskKey", invocation.taskKey);
      node.put("kind", invocation.kind);
      node.put("recordId", invocation.recordId);
      node.put("promptHash", sha256(invocation.prompt));
      node.put("responseSchemaHash", sha256(invocation.schemaJson));
      node.put("controlledFault", invocation.controlled());
      node.put("requestArtifact", requestFile.toString());
      node.put("actualModelOutput", responseFile.toString());
      node.put("validationInput", validationFile.toString());
      node.put("responseSchemaArtifact", schemaFile.toString());
      node.set("assignedRecordIds", JSON.readTree(invocation.assignedRecordIds()));
      node.set("dependencyKeys", JSON.readTree(invocation.dependencyKeys()));
      node.put("inputFingerprint", invocation.inputFingerprint());
      node.set("portHashes", JSON.readTree(invocation.portHashes()));
      if (invocation == latest) {
        report.put("sanitizedResponse", responseFile.toString());
      }
      index++;
    }
    if (latest == null) {
      report.put("sanitizedResponse", "");
    }
    if (faultCase) {
      report.put("faultVerdict", verdict);
      writeFault(request, fault, verdict, document, invocations, artifacts);
    }
    writeJson(request.report().resolveSibling("final-document.json"), document);
    writeJson(request.report().resolveSibling("contract-provenance.json"), provenance(document));
    write(request.report(), report);
    int exit =
        switch (action) {
          case "READY_FOR_PRESENTATION" -> 0;
          case "WAITING_FOR_INPUT" -> 3;
          default -> 1;
        };
    if ("INVALID_INPUT".equals(failureCode) || "INVALID_ARGUMENT".equals(failureCode)) {
      return 2;
    }
    if (!failureCode.isBlank()) {
      return 1;
    }
    return exit;
  }

  private static void note(Fault fault, FillingResult result) {
    if (result == null) {
      return;
    }
    for (String reason : result.reasons()) {
      if ("MALFORMED_REFERENCE".equals(reason) && fault.detectionMechanism.isBlank()) {
        fault.detectionMechanism = "capture-validation";
        fault.detectionTask = result.taskId();
        fault.productionDetection = true;
      }
      if ("MISSING_RETAINED".equals(reason) && fault.detectionMechanism.isBlank()) {
        fault.detectionMechanism = "consumer-model-capture";
        fault.detectionTask = result.taskId();
        fault.scriptedConsumerReport = true;
        fault.productionDetection = false;
        fault.omitRetained = false;
      }
      if ("INPUT_DEFECT".equals(reason) && fault.detectionMechanism.isBlank() && "om-semantic-recovery".equals(fault.caseId)) {
        fault.detectionMechanism = "model-capture";
        fault.detectionTask = result.taskId();
      }
    }
  }

  private static String verdict(String caseId, Fault fault) {
    if (!faultCase(caseId)) {
      return "";
    }
    if (!fault.injectionApplied) {
      return "injection-not-applied";
    }
    if ("om-semantic-recovery".equals(caseId)) {
      if (fault.detectionMechanism.isBlank()) {
        return "undetected";
      }
      return fault.correctiveModelCalls > 0 ? "detected-and-repaired" : "detected";
    }
    if ("om-upstream-recovery".equals(caseId)) {
      if (fault.downstreamReturn) {
        return "routed-to-outline-owner";
      }
      if (!fault.detectionMechanism.isBlank() && fault.detectionTask.startsWith("define-transfers-")) {
        return "early-prevention";
      }
      return "injection-not-applied";
    }
    if ("capture-validation".equals(fault.detectionMechanism) && fault.correctiveModelCalls > 0) {
      return "detected-and-repaired";
    }
    return fault.detectionMechanism.isBlank() ? "undetected" : "detected";
  }

  private static boolean terminal(FillingResult result) {
    return result.action() != FillingResult.Action.ADVANCED && result.action() != FillingResult.Action.RETRY_CURRENT;
  }

  private static boolean questionOpen(WorkDocumentService documents, String runId, String questionId) {
    JsonNode document = JSON.valueToTree(documents.read(runId).document());
    for (JsonNode question : document.path("progress").path("questions")) {
      if (questionId.equals(question.path("id").asText()) && "OPEN".equals(question.path("resolution").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean blockingFailure(WorkDocumentService documents, String runId) {
    JsonNode document = JSON.valueToTree(documents.read(runId).document());
    if (document.path("schemaVersion").asInt() != 2) {
      return true;
    }
    String content = document.path("sources").path(0).path("content").asText("");
    if (!content.contains("Subject = name") || content.contains("<run-id>") || content.contains("<effective-model-label>")) {
      return true;
    }
    if (!sha256(content).equals(document.path("sources").path(0).path("contentHash").asText())) {
      return true;
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      if ("Salesforce result".equals(step.path("label").asText())) {
        return true;
      }
    }
    return false;
  }

  private static String readyGap(JsonNode document, Cursor cursor, CheckpointRequest request) {
    if (!"om-progressive".equals(request.caseId())
        && !"om-controlled-recovery".equals(request.caseId())
        && !"om-upstream-recovery".equals(request.caseId())) {
      return null;
    }
    for (JsonNode row : checklist(document)) {
      if (!row.path("passed").asBoolean()) {
        return row.path("id").asText();
      }
    }
    if (cursor.mappingModelCalls < 3 || cursor.mappingModelCalls != cursor.mappingOneTransfer) {
      return "mapping-calls";
    }
    if (pending(document) != 0 || questions(document).size() != 0 || uncovered(document) != 0) {
      return "readiness";
    }
    if (request.sequentialFake() && syntheticSchemas(document) == 0) {
      return "schemas";
    }
    return null;
  }

  private static void emit(
      CheckpointRequest request,
      WorkDocumentService documents,
      ProductPipelineRunStore runs,
      String runId,
      String commandId,
      FillingResult result,
      List<Invocation> invocations,
      Fault fault,
      boolean append)
      throws Exception {
    Invocation match = null;
    int matchIndex = 0;
    for (int index = invocations.size() - 1; index >= 0; index--) {
      if (commandId.equals(invocations.get(index).commandId)) {
        match = invocations.get(index);
        matchIndex = index + 1;
        break;
      }
    }
    JsonNode document = JSON.valueToTree(documents.read(runId).document());
    ObjectNode event = traceEvent(request, documents, runId, commandId, result, match, matchIndex, document, runs, fault);
    boolean writing = append;
    if (match != null) {
      ObjectNode dispatch = event.deepCopy();
      dispatch.put("type", "dispatch");
      appendLine(request.report().resolveSibling("task-trace.jsonl"), dispatch, writing);
      writing = true;
    }
    ObjectNode recorded = event.deepCopy();
    recorded.put("type", "result");
    appendLine(request.report().resolveSibling("task-trace.jsonl"), recorded, writing);
    ObjectNode assertion = JSON.createObjectNode();
    assertion.put("commandId", commandId);
    assertion.put("inputTask", result.taskId());
    assertion.put("action", result.action().name());
    assertion.put("outputRevision", result.documentRevision());
    assertion.set("checks", checks(document));
    appendLine(request.report().resolveSibling("stage-assertions.jsonl"), assertion, append);
    boolean rejection = false;
    for (String reason : result.reasons()) {
      if ("MALFORMED_REFERENCE".equals(reason) || "MISSING_RETAINED".equals(reason) || "INPUT_DEFECT".equals(reason)) {
        rejection = true;
      }
    }
    if (rejection) {
      ObjectNode rejected = recorded.deepCopy();
      rejected.put("type", "rejection");
      appendLine(request.report().resolveSibling("task-trace.jsonl"), rejected, true);
      ObjectNode recovery = recorded.deepCopy();
      recovery.put("type", "recovery");
      appendLine(request.report().resolveSibling("task-trace.jsonl"), recovery, true);
    }
    if (result.action() == FillingResult.Action.WAITING_FOR_INPUT) {
      ObjectNode wait = recorded.deepCopy();
      wait.put("type", "wait");
      appendLine(request.report().resolveSibling("task-trace.jsonl"), wait, true);
    }
    if (!result.documentReference().isBlank()) {
      ObjectNode published = recorded.deepCopy();
      published.put("type", "publication");
      appendLine(request.report().resolveSibling("task-trace.jsonl"), published, true);
    }
  }

  private static ObjectNode traceEvent(
      CheckpointRequest request,
      WorkDocumentService documents,
      String runId,
      String commandId,
      FillingResult result,
      Invocation match,
      int matchIndex,
      JsonNode document,
      ProductPipelineRunStore runs,
      Fault fault) {
    ObjectNode event = JSON.createObjectNode();
    event.put("commandId", commandId);
    event.put("taskId", result.taskId());
    event.put("taskKey", match == null ? "" : match.taskKey());
    event.put("kind", match == null ? "" : match.kind());
    event.put("recordId", match == null ? "" : match.recordId());
    event.put("action", result.action().name());
    event.set("assignedRecordIds", jsonArray(match == null ? "[]" : match.assignedRecordIds()));
    event.set("dependencyKeys", jsonArray(match == null ? "[]" : match.dependencyKeys()));
    event.put("inputFingerprint", match == null ? "" : match.inputFingerprint());
    event.put("promptHash", match == null ? "" : sha256(match.prompt()));
    event.put("responseSchemaHash", match == null ? "" : sha256(match.schemaJson()));
    event.put("baseRevision", match == null ? "" : match.baseRevision());
    event.put("documentRevision", result.documentRevision());
    event.put("committedRevision", result.documentRevision());
    event.put("documentReference", result.documentReference());
    event.set("reasons", JSON.valueToTree(result.reasons()));
    event.set("passageRefs", passageRefs(document));
    event.set("producedRecordIds", producedIds(document, result.taskId(), match == null ? "" : match.taskKey()));
    event.set("portHashes", jsonArray(match == null ? "[]" : match.portHashes()));
    event.put("controlledFault", match != null && match.controlled());
    event.put("ownedChange", fault.changedField);
    event.set("preservedSiblingIds", siblingRuleIds(document, fault));
    String cause = causeKey(runId, document, result.reasons());
    event.put("causeKey", cause);
    event.put("repairCharges", repairCharges(documents, runs, runId, cause));
    if (match != null) {
      Path artifacts = request.report().resolveSibling("artifacts");
      String name = "call-" + matchIndex;
      event.put("requestArtifact", artifacts.resolve(name + "-request.txt").toString());
      event.put("responseArtifact", artifacts.resolve(name + "-model-response.txt").toString());
      event.put("validationArtifact", artifacts.resolve(name + "-validation-input.txt").toString());
      event.put("responseSchemaArtifact", artifacts.resolve(name + "-schema.json").toString());
    } else {
      event.put("requestArtifact", "");
      event.put("responseArtifact", "");
      event.put("validationArtifact", "");
      event.put("responseSchemaArtifact", "");
    }
    return event;
  }

  private static void emitAnswer(CheckpointRequest request, String questionId, String inputId, boolean append)
      throws Exception {
    ObjectNode event = JSON.createObjectNode();
    event.put("type", "answer");
    event.put("questionId", questionId);
    event.put("inputId", inputId);
    appendLine(request.report().resolveSibling("task-trace.jsonl"), event, append);
  }

  static ArrayNode checks(JsonNode document) {
    ArrayNode checks = JSON.createArrayNode();
    ObjectNode version = checks.addObject();
    version.put("field", "schemaVersion");
    version.put("expected", "2");
    version.put("observed", document.path("schemaVersion").asText());
    version.put("passed", document.path("schemaVersion").asInt() == 2);
    String content = document.path("sources").path(0).path("content").asText("");
    ObjectNode subject = checks.addObject();
    subject.put("field", "source.subject");
    subject.put("expected", "Subject = name");
    subject.put("observed", content.contains("Subject = name") ? "present" : "absent");
    subject.put("passed", content.contains("Subject = name"));
    ObjectNode receive = checks.addObject();
    receive.put("field", "flow.extraReceive");
    boolean extra = false;
    for (JsonNode step : document.path("flow").path("steps")) {
      if ("Salesforce result".equals(step.path("label").asText())) {
        extra = true;
      }
    }
    receive.put("expected", "absent");
    receive.put("observed", extra ? "present" : "absent");
    receive.put("passed", !extra);
    ObjectNode sources = checks.addObject();
    sources.put("field", "sourcePaths");
    ArrayNode observedSources = sources.putArray("observed");
    boolean sourcesReal = true;
    for (String path : sourcePaths(document)) {
      observedSources.add(path);
      if (!path.startsWith("$.")) {
        sourcesReal = false;
      }
    }
    sources.put("passed", sourcesReal);
    ObjectNode retained = checks.addObject();
    retained.put("field", "retainedIds");
    ArrayNode observedRetained = retained.putArray("observed");
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        String id = value.path("id").asText();
        if (!id.isBlank()) {
          observedRetained.add(id);
        }
      }
    }
    retained.put("passed", true);
    ObjectNode tasks = checks.addObject();
    tasks.put("field", "taskStates");
    ArrayNode observedTasks = tasks.putArray("observed");
    for (JsonNode task : document.path("progress").path("tasks")) {
      observedTasks.add(task.path("taskKey").asText() + " " + task.path("state").asText());
    }
    tasks.put("passed", true);
    ObjectNode description = checks.addObject();
    description.put("field", "descriptionSources");
    description.put("passed", descriptionSources(document));
    return checks;
  }

  static ArrayNode checklist(JsonNode document) {
    ArrayNode list = JSON.createArrayNode();
    check(
        list,
        "subject-fallback",
        "$.name $.subRequestType $.orderId",
        sourcePath(document, "$.Subject", "$.name")
            && sourcePath(document, "$.Subject", "$.subRequestType")
            && sourcePath(document, "$.Subject", "$.orderId"));
    check(
        list,
        "priority-branches",
        "$.priority high urgent critical low Normal",
        sourcePath(document, "$.Priority", "$.priority") && priorityBranches(rule(document, "$.Priority")));
    check(list, "status-constant", "Not Started", constantValue(document, "$.Status", "Not Started"));
    check(
        list,
        "activity-date",
        "$.parameters.orderCreationDate",
        sourcePath(document, "$.ActivityDate", "$.parameters.orderCreationDate"));
    check(
        list,
        "description-names",
        "$.taskId $.executionId $.executionNumber $.orderType $.subRequestType $.woOrderType $.parameters",
        descriptionSources(document));
    check(
        list,
        "failure-code",
        "SALESFORCE_TASK_CREATE_ERROR",
        constantValue(document, "$.error.code", "SALESFORCE_TASK_CREATE_ERROR"));
    check(
        list,
        "error-text",
        "$.error.message",
        sourcePath(document, "$.error.message", "$.status"));
    check(list, "process-id", "$.processInstanceId $.processId", processRelationship(document));
    check(
        list,
        "retained-values",
        "$.executionId $.orderId $.processInstanceId $.executionNumber $.taskId",
        retainedPath(document, "$.executionId")
            && retainedPath(document, "$.orderId")
            && retainedPath(document, "$.processInstanceId")
            && retainedPath(document, "$.executionNumber")
            && retainedPath(document, "$.taskId"));
    check(list, "response-command", "completeTask", constantValue(document, "$.commandType", "completeTask"));
    check(list, "response-source-app", "salesforce", constantValue(document, "$.sourceAppName", "salesforce"));
    check(
        list,
        "response-echoes",
        "$.executionId $.orderId $.executionNumber $.taskId",
        echo(document, "$.executionId")
            && echo(document, "$.orderId")
            && echo(document, "$.executionNumber")
            && echo(document, "$.taskId"));
    check(
        list,
        "salesforce-task-id",
        "$.parameters.salesforceTaskId",
        sourcePath(document, "$.parameters.salesforceTaskId", "$.id"));
    return list;
  }

  private static void check(ArrayNode list, String id, String expected, boolean passed) {
    ObjectNode item = list.addObject();
    item.put("id", id);
    item.put("expected", expected);
    item.put("passed", passed);
  }

  private static void writeFault(
      CheckpointRequest request,
      Fault fault,
      String verdict,
      JsonNode document,
      List<Invocation> invocations,
      Path artifacts)
      throws Exception {
    String actualPath = "";
    String validationPath = "";
    int index = 1;
    for (Invocation invocation : invocations) {
      if (invocation.controlled()) {
        String name = "call-" + index;
        actualPath = artifacts.resolve(name + "-model-response.txt").toString();
        validationPath = artifacts.resolve(name + "-validation-input.txt").toString();
      }
      index++;
    }
    ObjectNode body = JSON.createObjectNode();
    body.put("caseId", request.caseId());
    body.put("injectionApplied", fault.injectionApplied);
    body.put("changedField", fault.changedField);
    body.put("actualModelOutput", actualPath);
    body.put("validationInput", validationPath);
    body.put("controlledFault", fault.injectionApplied);
    body.put("detectionMechanism", fault.detectionMechanism);
    body.put("detectionTask", fault.detectionTask);
    body.put("productionDetection", fault.productionDetection);
    body.put("scriptedConsumerReport", fault.scriptedConsumerReport);
    body.put("scriptedSemanticDetection", false);
    body.put("expectedOwner", expectedOwner(request.caseId()));
    body.put("actualRepairKind", fault.actualRepairKind);
    body.put("downstreamReturn", fault.downstreamReturn);
    body.put("earlyPrevention", "early-prevention".equals(verdict));
    body.put("correctiveModelCalls", fault.correctiveModelCalls);
    body.put("lateDiscoveryProvenSeparately", false);
    ArrayNode rechecks = body.putArray("recheckedConsumers");
    for (JsonNode task : document.path("progress").path("tasks")) {
      if ("NEEDS_RECHECK".equals(task.path("state").asText())) {
        rechecks.add(task.path("taskKey").asText());
      }
    }
    body.set("preservedSiblingIds", siblingRuleIds(document, fault));
    body.put("repairCharges", recoveryFindings(document));
    body.put(
        "lateDiscoveryInterfaceTest",
        "om-upstream-recovery".equals(request.caseId())
            ? "WorkFillingFaultInjectionTest.n04ConsumerFindsTheMissingRetainedDeclarationAfterTheOutline"
            : "");
    body.put("verdict", verdict);
    ArrayNode preserved = body.putArray("preservedRecordIds");
    for (String id : fault.preservedIds) {
      preserved.add(id);
    }
    boolean stillThere = true;
    for (String id : fault.preservedIds) {
      if (!containsStep(document, id)) {
        stillThere = false;
      }
    }
    body.put("preservedRecordsRemain", stillThere);
    writeJson(request.report().resolveSibling("fault-report.json"), body);
  }

  private static String expectedOwner(String caseId) {
    return switch (caseId) {
      case "om-controlled-recovery" -> "MAP_TRANSFER";
      case "om-upstream-recovery" -> "DEFINE_TRANSFERS";
      case "om-semantic-recovery" -> "SEMANTIC_REVIEW";
      default -> "";
    };
  }

  private static boolean containsStep(JsonNode document, String id) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (id.equals(step.path("id").asText())) {
        return true;
      }
    }
    return false;
  }

  private static ObjectNode caseBindings(JsonNode document) {
    ObjectNode bindings = JSON.createObjectNode();
    for (JsonNode step : document.path("flow").path("steps")) {
      if ("onTaskStart".equals(step.path("label").asText())) {
        bindings.put("triggerStepId", step.path("id").asText());
        bindings.put("triggerPayloadPort", portNamed(step, "payload"));
      }
      if ("onTaskResult".equals(step.path("label").asText())) {
        bindings.put("replyStepId", step.path("id").asText());
        bindings.put("replyRequestPort", portNamed(step, "request"));
      }
    }
    return bindings;
  }

  static String portNamed(JsonNode step, String name) {
    for (JsonNode port : step.path("binding").path("exposedPorts")) {
      if (name.equals(port.asText())) {
        return name;
      }
    }
    return "";
  }

  private static ArrayNode provenance(JsonNode document) {
    ArrayNode rows = JSON.createArrayNode();
    for (JsonNode step : document.path("flow").path("steps")) {
      JsonNode binding = step.path("binding");
      if (!binding.isObject()) {
        continue;
      }
      for (JsonNode hash : binding.path("portContentHashes")) {
        ObjectNode row = rows.addObject();
        String contentHash = hash.path("contentHash").asText();
        row.put("stepId", step.path("id").asText());
        row.put("operationId", binding.path("operationId").asText());
        row.put("version", binding.path("version").asText());
        row.put("port", hash.path("port").asText());
        row.put("contentHash", contentHash);
        row.put("reference", binding.path("contractReferences").path(0).asText());
        row.put("synthetic", syntheticContentHash(contentHash));
      }
    }
    return rows;
  }

  private static ArrayNode questions(JsonNode document) {
    ArrayNode questions = JSON.createArrayNode();
    for (JsonNode question : document.path("progress").path("questions")) {
      if (!"OPEN".equals(question.path("resolution").asText()) || "NEXT_ACTION".equals(question.path("choice").asText())) {
        continue;
      }
      ObjectNode item = questions.addObject();
      item.put("id", question.path("id").asText());
      JsonNode subject = question.path("subject");
      item.put("choiceKind", subject.path("choiceKind").asText());
      item.set("source", field(subject.path("source")));
      item.set("target", field(subject.path("target")));
    }
    return questions;
  }

  private static ObjectNode field(JsonNode ref) {
    ObjectNode node = JSON.createObjectNode();
    node.put("stepId", ref.path("stepId").asText());
    node.put("port", ref.path("port").asText());
    node.put("fieldPath", ref.path("fieldPath").asText());
    return node;
  }

  private static int pending(JsonNode document) {
    int count = 0;
    for (JsonNode task : document.path("progress").path("tasks")) {
      if (!"ACCEPTED".equals(task.path("state").asText())) {
        count++;
      }
    }
    return count;
  }

  private static int uncovered(JsonNode document) {
    List<String> covered = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode entry : step.path("data").path("outline").path("coverage")) {
        covered.add(entry.path("requirementId").asText());
      }
    }
    int missing = 0;
    for (JsonNode requirement : document.path("requirements")) {
      if (!covered.contains(requirement.path("id").asText())) {
        missing++;
      }
    }
    return missing;
  }

  static boolean syntheticContentHash(String hash) {
    if (hash == null || hash.length() != 64) {
      return true;
    }
    for (int index = 0; index < hash.length(); index++) {
      char character = hash.charAt(index);
      boolean hex = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
      if (!hex) {
        return true;
      }
    }
    return false;
  }

  private static int syntheticSchemas(JsonNode document) {
    int count = 0;
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode hash : step.path("binding").path("portContentHashes")) {
        if (syntheticContentHash(hash.path("contentHash").asText())) {
          count++;
        }
      }
    }
    return count;
  }

  private static boolean ownsOneTransfer(WorkDocumentService documents, String runId, WorkTaskRequest task) {
    String recordId = recordId(task);
    JsonNode document = JSON.valueToTree(documents.read(runId).document());
    int owners = 0;
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        if (recordId.equals(transfer.path("id").asText())) {
          owners++;
          continue;
        }
        for (JsonNode rule : transfer.path("rules")) {
          if (recordId.equals(rule.path("id").asText())) {
            owners++;
          }
        }
      }
    }
    return owners == 1;
  }

  private static String recordId(WorkTaskRequest request) {
    String id = request.taskId();
    for (String prefix :
        List.of(
            "logical-design-",
            "select-operation-",
            "define-transfers-",
            "describe-context-",
            "map-transfer-",
            "repair-rule-")) {
      if (id.startsWith(prefix)) {
        return id.substring(prefix.length());
      }
    }
    return id;
  }

  private static String behaviors(JsonNode document) {
    StringBuilder text = new StringBuilder();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          text.append(rule.path("behavior").asText()).append('\n');
        }
      }
    }
    return text.toString();
  }

  private static String constants(JsonNode document) {
    StringBuilder text = new StringBuilder();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          for (JsonNode constant : rule.path("constants")) {
            text.append(constant.path("value").asText()).append('\n');
          }
        }
      }
    }
    return text.toString();
  }

  private static List<String> targets(JsonNode document) {
    List<String> targets = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          targets.add(rule.path("target").path("fieldPath").asText());
        }
      }
    }
    return targets;
  }

  private static JsonNode reload(CheckpointRequest request, WorkDocumentService documents) {
    ArtifactBlobStore store = request.publicationStore();
    if (store instanceof FileCheckpointBlobStore file) {
      Clock clock = Clock.systemUTC();
      ArtifactBlobStore reopened = FileCheckpointBlobStore.open(file.root());
      CompilationArtifacts artifacts = new CompilationArtifacts(reopened, JSON, clock);
      ProductPipelineRunStore runs = new ProductPipelineRunStore(reopened, JSON, clock);
      WorkDocumentService fresh = new WorkDocumentService(runs, artifacts, JSON);
      return JSON.valueToTree(fresh.read(request.runId()).document());
    }
    return JSON.valueToTree(documents.read(request.runId()).document());
  }

  private static JsonNode caseSpec(CheckpointRequest request) throws Exception {
    if (!CASES.contains(request.caseId())) {
      return null;
    }
    JsonNode root = JSON.readTree(request.fixtureRoot().resolve("filling-cases.json").toFile());
    for (JsonNode item : root.path("cases")) {
      if (request.caseId().equals(item.path("id").asText())) {
        return item;
      }
    }
    return null;
  }

  private static String validate(WorkCheckpointHarness.CliArgs args) {
    if (args.runId.isBlank() || args.runId.contains("/") || args.runId.contains("..")) {
      return "INVALID_ARGUMENT";
    }
    if (args.report == null || !args.report.isAbsolute()) {
      return "INVALID_ARGUMENT";
    }
    if (args.inputFile != null && !args.resume) {
      return "INVALID_ARGUMENT";
    }
    if (args.inputFile != null && !args.inputFile.isAbsolute()) {
      return "INVALID_ARGUMENT";
    }
    if (!CASES.contains(args.caseId)) {
      return "UNKNOWN_CASE";
    }
    return null;
  }

  private static String validateRequest(CheckpointRequest request) {
    if (request.runId() == null || request.runId().isBlank() || request.runId().contains("/") || request.runId().contains("..")) {
      return "INVALID_ARGUMENT";
    }
    if (request.report() == null || !request.report().isAbsolute()) {
      return "INVALID_ARGUMENT";
    }
    if (request.inputFile() != null && !request.resume()) {
      return "INVALID_ARGUMENT";
    }
    if (request.inputFile() != null && !request.inputFile().isAbsolute()) {
      return "INVALID_ARGUMENT";
    }
    return null;
  }

  private static String invalidMessage(String code) {
    return switch (code) {
      case "UNKNOWN_CASE" -> "Case is not a filling checkpoint id.";
      case "INVALID_INPUT" -> "The input file needs inputId, questionId, and text.";
      default -> "The filling arguments are not valid. Check run id, absolute paths, and --resume.";
    };
  }

  private static void writeInvalid(WorkCheckpointHarness.CliArgs args, String code) throws Exception {
    if (args.report == null) {
      System.err.println(invalidMessage(code));
      return;
    }
    write(args.report, failed(args.checkpoint, args.caseId, code, invalidMessage(code)));
  }

  private static ObjectNode base(CheckpointRequest request, CheckpointSession session, JsonNode spec) {
    ObjectNode report = JSON.createObjectNode();
    report.put("checkpoint", request.checkpoint());
    report.put("caseId", request.caseId());
    report.put("gate", spec.path("gate").asText());
    report.put("gateModel", WorkCheckpointHarness.GATE_MODEL);
    report.put("requiredObservation", spec.path("observation").asText());
    report.put("effectiveProvider", session.provider());
    report.put("effectiveModel", session.model());
    report.put("providerSwitched", session.providerSwitched());
    report.put("materialized", false);
    report.put("durable", true);
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
    report.put("gateModel", WorkCheckpointHarness.GATE_MODEL);
    return report;
  }

  private static ObjectNode failed(String checkpoint, String caseId, String code, String message) {
    ObjectNode report = refused(checkpoint, caseId, code, message);
    report.put("outcome", "FAILED");
    return report;
  }

  private static Cursor readCursor(ArtifactBlobStore store, String runId) throws Exception {
    String key = "harness/cursor-" + runId;
    if (store.get(key).isEmpty()) {
      return new Cursor();
    }
    JsonNode node = JSON.readTree(store.get(key).orElseThrow());
    Cursor cursor = new Cursor();
    cursor.pending = node.path("pending").asText("");
    cursor.next = node.path("next").asInt(1);
    cursor.modelCalls = node.path("modelCalls").asInt(0);
    cursor.mappingModelCalls = node.path("mappingModelCalls").asInt(0);
    cursor.mappingOneTransfer = node.path("mappingOneTransfer").asInt(0);
    if (cursor.next < 1) {
      cursor.next = 1;
    }
    return cursor;
  }

  private static List<Invocation> readInvocations(ArtifactBlobStore store, String runId) throws Exception {
    String key = "harness/invocations-" + runId;
    if (store.get(key).isEmpty()) {
      return new ArrayList<>();
    }
    List<Invocation> list = new ArrayList<>();
    for (JsonNode node : JSON.readTree(store.get(key).orElseThrow())) {
      list.add(
          new Invocation(
              node.path("commandId").asText(),
              node.path("taskId").asText(),
              node.path("taskKey").asText(),
              node.path("kind").asText(),
              node.path("recordId").asText(),
              node.path("prompt").asText(),
              node.path("schemaJson").asText(),
              node.path("actualOutput").asText(),
              node.path("validationInput").asText(),
              node.path("controlled").asBoolean(),
              node.path("assignedRecordIds").isMissingNode() ? "[]" : node.path("assignedRecordIds").toString(),
              node.path("dependencyKeys").isMissingNode() ? "[]" : node.path("dependencyKeys").toString(),
              node.path("inputFingerprint").asText(""),
              node.path("portHashes").isMissingNode() ? "[]" : node.path("portHashes").toString(),
              node.path("baseRevision").asText("")));
    }
    return list;
  }

  private static void saveInvocations(ArtifactBlobStore store, String runId, List<Invocation> invocations)
      throws Exception {
    ArrayNode array = JSON.createArrayNode();
    for (Invocation invocation : invocations) {
      ObjectNode node = array.addObject();
      node.put("commandId", invocation.commandId());
      node.put("taskId", invocation.taskId());
      node.put("taskKey", invocation.taskKey());
      node.put("kind", invocation.kind());
      node.put("recordId", invocation.recordId());
      node.put("prompt", invocation.prompt());
      node.put("schemaJson", invocation.schemaJson());
      node.put("actualOutput", invocation.actualOutput());
      node.put("validationInput", invocation.validationInput());
      node.put("controlled", invocation.controlled());
      node.set("assignedRecordIds", jsonArray(invocation.assignedRecordIds()));
      node.set("dependencyKeys", jsonArray(invocation.dependencyKeys()));
      node.put("inputFingerprint", invocation.inputFingerprint());
      node.set("portHashes", jsonArray(invocation.portHashes()));
      node.put("baseRevision", invocation.baseRevision());
    }
    store.put("harness/invocations-" + runId, JSON.writeValueAsBytes(array));
  }

  private static void saveCursor(ArtifactBlobStore store, String runId, Cursor cursor) throws Exception {
    ObjectNode node = JSON.createObjectNode();
    node.put("pending", cursor.pending);
    node.put("next", cursor.next);
    node.put("modelCalls", cursor.modelCalls);
    node.put("mappingModelCalls", cursor.mappingModelCalls);
    node.put("mappingOneTransfer", cursor.mappingOneTransfer);
    store.put("harness/cursor-" + runId, JSON.writeValueAsBytes(node));
  }

  private static Fault readFault(ArtifactBlobStore store, String runId, String caseId) throws Exception {
    String key = "harness/fault-" + runId;
    Fault fault = new Fault(caseId);
    if (store.get(key).isEmpty()) {
      fault.omitRetained = "om-upstream-recovery".equals(caseId);
      return fault;
    }
    JsonNode node = JSON.readTree(store.get(key).orElseThrow());
    fault.injectionApplied = node.path("injectionApplied").asBoolean();
    fault.changedField = node.path("changedField").asText("");
    fault.original = node.path("original").asText("");
    fault.mutated = node.path("mutated").asText("");
    fault.detectionMechanism = node.path("detectionMechanism").asText("");
    fault.detectionTask = node.path("detectionTask").asText("");
    fault.actualRepairKind = node.path("actualRepairKind").asText("");
    fault.downstreamReturn = node.path("downstreamReturn").asBoolean();
    fault.correctiveModelCalls = node.path("correctiveModelCalls").asInt();
    fault.productionDetection = node.path("productionDetection").asBoolean();
    fault.scriptedConsumerReport = node.path("scriptedConsumerReport").asBoolean();
    fault.omitRetained = node.path("omitRetained").asBoolean();
    fault.controlledUsed = node.path("controlledUsed").asBoolean();
    fault.semanticUsed = node.path("semanticUsed").asBoolean();
    fault.reportedMissing = node.path("reportedMissing").asBoolean();
    for (JsonNode id : node.path("preservedIds")) {
      fault.preservedIds.add(id.asText());
    }
    return fault;
  }

  private static void saveFault(ArtifactBlobStore store, String runId, Fault fault) throws Exception {
    ObjectNode node = JSON.createObjectNode();
    node.put("injectionApplied", fault.injectionApplied);
    node.put("changedField", fault.changedField);
    node.put("original", fault.original);
    node.put("mutated", fault.mutated);
    node.put("detectionMechanism", fault.detectionMechanism);
    node.put("detectionTask", fault.detectionTask);
    node.put("actualRepairKind", fault.actualRepairKind);
    node.put("downstreamReturn", fault.downstreamReturn);
    node.put("correctiveModelCalls", fault.correctiveModelCalls);
    node.put("productionDetection", fault.productionDetection);
    node.put("scriptedConsumerReport", fault.scriptedConsumerReport);
    node.put("omitRetained", fault.omitRetained);
    node.put("controlledUsed", fault.controlledUsed);
    node.put("semanticUsed", fault.semanticUsed);
    node.put("reportedMissing", fault.reportedMissing);
    ArrayNode ids = node.putArray("preservedIds");
    for (String id : fault.preservedIds) {
      ids.add(id);
    }
    store.put("harness/fault-" + runId, JSON.writeValueAsBytes(node));
  }

  private static Path canonicalSource() {
    Path module = Path.of("").toAbsolutePath();
    Path sibling = module.resolve("../.scratch/progressive-chain-work-document/fixtures/om-salesforce.md");
    if (Files.exists(sibling)) {
      return sibling.normalize();
    }
    Path local = module.resolve(".scratch/progressive-chain-work-document/fixtures/om-salesforce.md");
    if (Files.exists(local)) {
      return local;
    }
    throw new IllegalStateException("SOURCE_UNAVAILABLE: canonical source file is missing.");
  }

  private static String canonicalBlock(String fileText) {
    int open = fileText.indexOf("```text");
    int body = open < 0 ? -1 : fileText.indexOf('\n', open);
    int close = body < 0 ? -1 : fileText.indexOf("```", body + 1);
    if (open < 0 || body < 0 || close < 0) {
      throw new IllegalStateException("SOURCE_UNAVAILABLE: canonical text block is missing.");
    }
    return fileText.substring(body + 1, close).trim();
  }

  private static String modelLabel(String model) {
    if (model == null || model.isBlank()) {
      return "unspecified-model";
    }
    return model;
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static boolean faultCase(String caseId) {
    return "om-controlled-recovery".equals(caseId)
        || "om-upstream-recovery".equals(caseId)
        || "om-semantic-recovery".equals(caseId);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static void appendLine(Path file, ObjectNode event, boolean append) throws Exception {
    if (file.getParent() != null) {
      Files.createDirectories(file.getParent());
    }
    String line = JSON.writeValueAsString(event) + "\n";
    if (append && Files.exists(file)) {
      Files.writeString(file, line, StandardOpenOption.APPEND);
    } else {
      Files.writeString(file, line);
    }
  }

  private static void writeJson(Path file, JsonNode body) throws Exception {
    if (file.getParent() != null) {
      Files.createDirectories(file.getParent());
    }
    JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), body);
  }

  private static void write(Path report, ObjectNode body) throws Exception {
    writeJson(report, body);
  }

  private static final class Cursor {
    private String pending = "";
    private int next = 1;
    private int modelCalls;
    private int mappingModelCalls;
    private int mappingOneTransfer;
  }

  private static final class Fault {
    private final String caseId;
    private boolean injectionApplied;
    private String changedField = "";
    private String original = "";
    private String mutated = "";
    private String detectionMechanism = "";
    private String detectionTask = "";
    private String actualRepairKind = "";
    private boolean downstreamReturn;
    private int correctiveModelCalls;
    private boolean productionDetection;
    private boolean scriptedConsumerReport;
    private boolean omitRetained;
    private boolean controlledUsed;
    private boolean semanticUsed;
    private boolean reportedMissing;
    private final List<String> preservedIds = new ArrayList<>();

    private Fault(String caseId) {
      this.caseId = caseId;
    }

    private String apply(WorkTaskKind kind, String actual, JsonNode document) {
      try {
        if ("om-controlled-recovery".equals(caseId) && !controlledUsed && kind == WorkTaskKind.MAP_TRANSFER) {
          JsonNode tree = JSON.readTree(actual);
          String previous = firstSourceRef(tree);
          if (!previous.isBlank()) {
            ObjectNode copy = tree.deepCopy();
            replaceFirstSourceRef(copy, "missing-step/payload");
            controlledUsed = true;
            remember(document, "rules.sourceRef", actual, JSON.writeValueAsString(copy));
            return mutated;
          }
        }
        if ("om-semantic-recovery".equals(caseId) && !semanticUsed && kind == WorkTaskKind.MAP_TRANSFER) {
          JsonNode tree = JSON.readTree(actual);
          ObjectNode copy = tree.deepCopy();
          if (dropPriorityBranch(copy)) {
            semanticUsed = true;
            remember(document, "rules.behavior", actual, JSON.writeValueAsString(copy));
            return mutated;
          }
        }
        if (omitRetained && kind == WorkTaskKind.DEFINE_TRANSFERS) {
          ObjectNode copy = (ObjectNode) JSON.readTree(actual);
          if (copy.path("retainedPlaceholders").size() > 0 || hasRetained(copy)) {
            String before = actual;
            if (failurePort(copy)) {
              for (JsonNode transfer : copy.path("transfers")) {
                if (transfer instanceof ObjectNode object
                    && "success".equals(object.path("sourcePort").asText())) {
                  object.putArray("requiredRetainedIds");
                }
              }
            } else {
              copy.putArray("retainedPlaceholders");
              for (JsonNode transfer : copy.path("transfers")) {
                if (transfer instanceof ObjectNode object) {
                  object.putArray("requiredRetainedIds");
                }
              }
            }
            String after = JSON.writeValueAsString(copy);
            if (!injectionApplied) {
              remember(
                  document,
                  failurePort(copy) ? "requiredRetainedIds" : "retainedPlaceholders",
                  before,
                  after);
            }
            return after;
          }
        }
      } catch (Exception failure) {
        return actual;
      }
      return actual;
    }

    private void remember(JsonNode document, String field, String before, String after) {
      injectionApplied = true;
      changedField = field;
      original = before;
      mutated = after;
      if (preservedIds.isEmpty()) {
        for (JsonNode step : document.path("flow").path("steps")) {
          String id = step.path("id").asText();
          if (!id.isBlank()) {
            preservedIds.add(id);
          }
        }
      }
    }

    private static String firstSourceRef(JsonNode tree) {
      for (JsonNode rule : tree.path("rules")) {
        for (JsonNode source : rule.path("sources")) {
          String ref = source.path("sourceRef").asText();
          if (!ref.isBlank()) {
            return ref;
          }
        }
      }
      return "";
    }

    private static void replaceFirstSourceRef(ObjectNode tree, String replacement) {
      for (JsonNode rule : tree.path("rules")) {
        for (JsonNode source : rule.path("sources")) {
          if (source instanceof ObjectNode object && !object.path("sourceRef").asText().isBlank()) {
            object.put("sourceRef", replacement);
            return;
          }
        }
      }
    }

    private static boolean dropPriorityBranch(ObjectNode tree) {
      for (JsonNode rule : tree.path("rules")) {
        if ("$.Priority".equals(rule.path("targetPath").asText()) && rule instanceof ObjectNode object) {
          String behavior = object.path("behavior").asText();
          if (behavior.contains("low")) {
            object.put("behavior", "high, urgent, or critical to High");
            return true;
          }
        }
      }
      return false;
    }

    private static boolean failurePort(JsonNode tree) {
      for (JsonNode transfer : tree.path("transfers")) {
        if ("failure".equals(transfer.path("sourcePort").asText())) {
          return true;
        }
      }
      return false;
    }

    private static boolean hasRetained(JsonNode tree) {
      for (JsonNode transfer : tree.path("transfers")) {
        if (transfer.path("requiredRetainedIds").size() > 0) {
          return true;
        }
      }
      return false;
    }
  }

  private static void applyRoute(Fault fault, JsonNode document) {
    for (JsonNode task : document.path("progress").path("tasks")) {
      if (!"corrective-target".equals(task.path("taskKey").asText())) {
        continue;
      }
      String kind = task.path("kind").asText();
      if (!kind.isBlank() && fault.actualRepairKind.isBlank()) {
        fault.actualRepairKind = kind;
      }
    }
  }

  private static WorkTaskPlanner.Task plannedTask(ChainWorkDocument document, String taskKey) {
    if (document == null || taskKey == null || taskKey.isBlank()) {
      return null;
    }
    for (WorkTaskPlanner.Task task : new WorkTaskPlanner().plan(document).tasks()) {
      if (taskKey.equals(task.taskKey())) {
        return task;
      }
    }
    return null;
  }

  private static JsonNode jsonArray(String text) {
    try {
      JsonNode node = JSON.readTree(text == null || text.isBlank() ? "[]" : text);
      return node.isArray() ? node : JSON.createArrayNode();
    } catch (Exception failure) {
      return JSON.createArrayNode();
    }
  }

  private static ArrayNode passageRefs(JsonNode document) {
    ArrayNode refs = JSON.createArrayNode();
    for (JsonNode source : document.path("sources")) {
      for (JsonNode passage : source.path("passages")) {
        String id = passage.path("id").asText();
        if (!id.isBlank()) {
          refs.add(id);
        }
      }
    }
    return refs;
  }

  private static ArrayNode producedIds(JsonNode document, String taskId, String taskKey) {
    ArrayNode ids = JSON.createArrayNode();
    for (JsonNode task : document.path("progress").path("tasks")) {
      boolean sameTask = taskId.equals(task.path("taskId").asText()) || taskKey.equals(task.path("taskKey").asText());
      if (!sameTask) {
        continue;
      }
      for (JsonNode id : task.path("producedRecordIds")) {
        ids.add(id.asText());
      }
    }
    return ids;
  }

  private static ArrayNode siblingRuleIds(JsonNode document, Fault fault) {
    ArrayNode ids = JSON.createArrayNode();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          String id = rule.path("id").asText();
          if (!id.isBlank() && !id.equals(fault.detectionTask)) {
            ids.add(id);
          }
        }
      }
    }
    return ids;
  }

  private static String causeKey(String runId, JsonNode document, List<String> reasons) {
    for (JsonNode finding : document.path("progress").path("findings")) {
      String category = finding.path("issueCategory").asText();
      boolean named = false;
      for (String reason : reasons) {
        if (reason.equals(category)) {
          named = true;
        }
      }
      if (!named) {
        continue;
      }
      return WorkRecovery.causeKey(
          runId,
          finding.path("recordRef").asText(),
          category,
          finding.path("canonicalFieldPointer").asText());
    }
    return "";
  }

  private static int repairCharges(
      WorkDocumentService documents, ProductPipelineRunStore runs, String runId, String cause) {
    if (documents == null || runs == null || cause == null || cause.isBlank()) {
      return 0;
    }
    try {
      return WorkRecovery.create(documents, runs).repairsRemaining(runId, cause);
    } catch (RuntimeException failure) {
      return 0;
    }
  }

  private static int recoveryFindings(JsonNode document) {
    int count = 0;
    for (JsonNode finding : document.path("progress").path("findings")) {
      String category = finding.path("issueCategory").asText();
      if ("MALFORMED_REFERENCE".equals(category)
          || "MISSING_RETAINED".equals(category)
          || "INPUT_DEFECT".equals(category)) {
        count++;
      }
    }
    return count;
  }

  private static ArrayNode portHashes(JsonNode document, String recordId, List<String> assigned) {
    ArrayNode hashes = JSON.createArrayNode();
    for (JsonNode step : document.path("flow").path("steps")) {
      if (!stepOwns(step, recordId, assigned)) {
        continue;
      }
      addHashes(hashes, step);
      for (JsonNode transfer : step.path("data").path("transfers")) {
        if (!transferOwns(transfer, recordId, assigned)) {
          continue;
        }
        for (JsonNode source : transfer.path("sourcePorts")) {
          addHashes(hashes, stepById(document, source.path("stepId").asText()));
        }
      }
    }
    return hashes;
  }

  private static boolean stepOwns(JsonNode step, String recordId, List<String> assigned) {
    if (recordId.equals(step.path("id").asText()) || assigned.contains(step.path("id").asText())) {
      return true;
    }
    for (JsonNode transfer : step.path("data").path("transfers")) {
      if (transferOwns(transfer, recordId, assigned)) {
        return true;
      }
    }
    return false;
  }

  private static boolean transferOwns(JsonNode transfer, String recordId, List<String> assigned) {
    if (recordId.equals(transfer.path("id").asText()) || assigned.contains(transfer.path("id").asText())) {
      return true;
    }
    for (JsonNode rule : transfer.path("rules")) {
      if (recordId.equals(rule.path("id").asText()) || assigned.contains(rule.path("id").asText())) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode stepById(JsonNode document, String id) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (id.equals(step.path("id").asText())) {
        return step;
      }
    }
    return null;
  }

  private static void addHashes(ArrayNode hashes, JsonNode step) {
    if (step == null) {
      return;
    }
    JsonNode binding = step.path("binding");
    for (JsonNode hash : binding.path("portContentHashes")) {
      ObjectNode row = hashes.addObject();
      row.put("port", hash.path("port").asText());
      row.put("contentHash", hash.path("contentHash").asText());
      row.put("version", binding.path("version").asText());
      row.put("reference", binding.path("contractReferences").path(0).asText());
    }
  }

  private static List<String> sourcePaths(JsonNode document) {
    List<String> paths = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          for (JsonNode source : rule.path("sources")) {
            String path = source.path("fieldPath").asText();
            if (!path.isBlank()) {
              paths.add(path);
            }
          }
        }
      }
    }
    return paths;
  }

  private static JsonNode rule(JsonNode document, String target) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          if (target.equals(rule.path("target").path("fieldPath").asText())) {
            return rule;
          }
        }
      }
    }
    return null;
  }

  private static boolean sourcePath(JsonNode document, String target, String path) {
    JsonNode found = rule(document, target);
    if (found == null) {
      return false;
    }
    for (JsonNode source : found.path("sources")) {
      if (path.equals(source.path("fieldPath").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean descriptionSources(JsonNode document) {
    return sourcePath(document, "$.Description", "$.taskId")
        && sourcePath(document, "$.Description", "$.executionId")
        && sourcePath(document, "$.Description", "$.executionNumber")
        && sourcePath(document, "$.Description", "$.orderType")
        && sourcePath(document, "$.Description", "$.subRequestType")
        && sourcePath(document, "$.Description", "$.woOrderType")
        && sourcePath(document, "$.Description", "$.parameters");
  }

  private static boolean priorityBranches(JsonNode rule) {
    if (rule == null) {
      return false;
    }
    String behavior = rule.path("behavior").asText();
    return behavior.contains("high")
        && behavior.contains("urgent")
        && behavior.contains("critical")
        && behavior.contains("low")
        && behavior.contains("Normal");
  }

  private static boolean constantValue(JsonNode document, String target, String value) {
    JsonNode found = rule(document, target);
    if (found == null) {
      return false;
    }
    for (JsonNode constant : found.path("constants")) {
      if (value.equals(constant.path("value").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean retainedPath(JsonNode document, String path) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        if (path.equals(value.path("source").path("fieldPath").asText())) {
          return true;
        }
      }
    }
    return false;
  }

  private static String retainedField(JsonNode document, String retainedId) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        if (retainedId.equals(value.path("id").asText())) {
          return value.path("source").path("fieldPath").asText();
        }
      }
    }
    return "";
  }

  private static boolean echo(JsonNode document, String target) {
    JsonNode found = rule(document, target);
    if (found == null) {
      return false;
    }
    for (JsonNode source : found.path("sources")) {
      String retainedId = source.path("retainedValueId").asText();
      if (!retainedId.isBlank() && target.equals(retainedField(document, retainedId))) {
        return true;
      }
      if (target.equals(source.path("fieldPath").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean processRelationship(JsonNode document) {
    JsonNode found = rule(document, "$.processId");
    if (found == null) {
      return false;
    }
    for (JsonNode source : found.path("sources")) {
      String retainedId = source.path("retainedValueId").asText();
      if ("$.processInstanceId".equals(retainedField(document, retainedId))) {
        return true;
      }
      if ("$.processInstanceId".equals(source.path("fieldPath").asText())) {
        return true;
      }
    }
    return false;
  }

  private record Invocation(
      String commandId,
      String taskId,
      String taskKey,
      String kind,
      String recordId,
      String prompt,
      String schemaJson,
      String actualOutput,
      String validationInput,
      boolean controlled,
      String assignedRecordIds,
      String dependencyKeys,
      String inputFingerprint,
      String portHashes,
      String baseRevision) {

    private static Invocation of(
        WorkTaskRequest task,
        String actual,
        String validation,
        Cursor cursor,
        WorkTaskPlanner.Task planned,
        JsonNode document,
        String baseRevision) {
      String schema;
      try {
        schema = JSON.writeValueAsString(JsonSchemaElementUtils.toMap(task.responseSchema(), true));
      } catch (Exception failure) {
        schema = "";
      }
      List<String> assigned = planned == null ? List.of() : planned.assignedRecordIds();
      List<String> dependencies = planned == null ? List.of() : planned.dependencyKeys();
      return new Invocation(
          cursor.pending.isBlank() ? "advance-" + Math.max(cursor.next - 1, 1) : cursor.pending,
          task.taskId(),
          task.taskKey(),
          task.kind().name(),
          FillingCheckpoint.recordId(task),
          task.prompt(),
          schema,
          actual,
          validation,
          !actual.equals(validation),
          JSON.valueToTree(assigned).toString(),
          JSON.valueToTree(dependencies).toString(),
          planned == null ? "" : planned.requiredInputFingerprint(),
          FillingCheckpoint.portHashes(document, FillingCheckpoint.recordId(task), assigned).toString(),
          baseRevision == null ? "" : baseRevision);
    }
  }
}
