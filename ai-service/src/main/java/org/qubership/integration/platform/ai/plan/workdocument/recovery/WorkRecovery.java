package org.qubership.integration.platform.ai.plan.workdocument.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecisionValidator;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.TechnicalFailureRecord;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptKey;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Routes a validated document finding to the responsibility that owns the record. One cause keeps
 * its ledger slot across stage hops. The model cannot mint a new identity for an open finding.
 */
public final class WorkRecovery {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final char FIELD = '\u0000';
  private static final String EXHAUSTED_PREFIX = "recovery-exhausted:";

  private final WorkDocumentService documents;
  private final ProductPipelineRunStore runs;
  private final RecoveryAttemptLedger ledger;

  public WorkRecovery(
      WorkDocumentService documents, ProductPipelineRunStore runs, RecoveryAttemptLedger ledger) {
    this.documents = documents;
    this.runs = runs;
    this.ledger = ledger == null ? documentLedger() : ledger;
  }

  public static WorkRecovery create(WorkDocumentService documents, ProductPipelineRunStore runs) {
    return new WorkRecovery(documents, runs, documentLedger());
  }

  public static String causeKey(
      String runId, String originRecordId, String issueCategory, String fieldPointer) {
    return runId + FIELD + originRecordId + FIELD + issueCategory + FIELD + fieldPointer;
  }

  public Result route(String runId, Defect defect, String commandId) {
    requireCommand(commandId);
    Defect request =
        defect == null ? new Defect("", "", "", "", "", List.of(), "", "") : defect;
    ProductPipelineRunDocument current = load(runId);
    String payloadHash = payloadHash("route", request);
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return resultFrom(runId, request, replay.get());
    }
    JsonNode document = JSON.valueToTree(documents.read(runId).document());
    String pointer = canonicalPointer(document, request.originRecordId(), request.fieldPointer());
    StoredCause stored = resolveStored(document, request, pointer);
    if (!knownRecord(document, stored.origin())) {
      throw rejected(
          "UNKNOWN_RECORD",
          "Record "
              + stored.origin()
              + " is not on the document. Name a record the document already stores.");
    }
    requireEvidence(document, request.evidenceIds());
    WorkStage originOwner = ownerOf(document, stored.origin(), stored.pointer());
    WorkStage owner = originOwner;
    if (!request.revealedRecordId().isBlank()) {
      if (!knownRecord(document, request.revealedRecordId())) {
        throw rejected(
            "UNKNOWN_RECORD",
            "Record "
                + request.revealedRecordId()
                + " is not on the document. Name a record the document already stores.");
      }
      WorkStage revealed =
          ownerOf(document, request.revealedRecordId(), request.revealedFieldPointer());
      if (revealed.ordinal() >= originOwner.ordinal()) {
        throw rejected(
            "NOT_EARLIER_OWNER",
            "Record "
                + request.revealedRecordId()
                + " belongs to the same or a later responsibility. Name an earlier record to continue this cause.");
      }
      owner = revealed;
    }
    String keyText = causeKey(runId, stored.origin(), stored.category(), stored.pointer());
    RecoveryAttemptKey key = ledger.documentCauseKey(keyText);
    boolean allowed =
        ledger.mayRepair(current.transitions(), key, InputOrigin.TRUSTED);
    ObjectNode next = (ObjectNode) document.deepCopy();
    String findingId =
        upsertFinding(
            next.withObject("progress"),
            stored,
            request.contradiction(),
            request.evidenceIds(),
            allowed);
    String nextAction = "";
    String reason;
    RecoveryDecision decision;
    Reference documentRef = current.run().workDocumentRef();
    if (allowed) {
      markTasks((ObjectNode) next.withObject("progress"), owner);
      reason = ledger.recordRepair(key, "");
      decision = businessDecision(documentRef, stored.origin(), owner, false);
    } else {
      owner = WorkStage.valueOf(current.run().currentStageId());
      nextAction = exhaustedAction(stored.origin());
      addNextAction((ObjectNode) next.withObject("progress"), stored.origin(), nextAction);
      reason = EXHAUSTED_PREFIX + keyText;
      decision = businessDecision(documentRef, stored.origin(), owner, true);
    }
    int remaining =
        ledger.remaining(current.transitions(), key, InputOrigin.TRUSTED).semanticRepairsRemaining();
    if (allowed) {
      remaining = Math.max(0, remaining - 1);
    }
    ChainWorkDocument recovered = JSON.convertValue(next, ChainWorkDocument.class);
    documents.commitRecoveredDocument(
        runId,
        recovered,
        commandId,
        payloadHash,
        reason,
        owner.name(),
        new WorkRepairBudget(remaining));
    return new Result(
        keyText, findingId, owner, allowed, !allowed, nextAction, remaining, decision);
  }

  public Result technicalRetry(
      String runId, Throwable failure, String commandId, int executorLimit) {
    requireCommand(commandId);
    boolean dispatched = chargeTechnical(runId, failure, commandId, executorLimit);
    Reference documentRef = load(runId).run().workDocumentRef();
    RecoveryDecision decision = transportDecision(documentRef, failure, dispatched);
    return new Result(
        "",
        "",
        null,
        dispatched,
        !dispatched,
        decision.userSummary(),
        0,
        decision);
  }

  public boolean nestedToolRetry(
      String runId, Throwable failure, String commandId, int executorLimit) {
    return chargeTechnical(runId, failure, commandId, executorLimit);
  }

  public String causeKey(String runId, String findingId) {
    StoredCause stored = findById(JSON.valueToTree(documents.read(runId).document()), findingId);
    if (stored == null) {
      throw rejected(
          "UNKNOWN_RECORD",
          "Finding " + findingId + " is not on the document. Read the document and use its finding id.");
    }
    return causeKey(runId, stored.origin(), stored.category(), stored.pointer());
  }

  public int repairsRemaining(String runId, String causeKey) {
    RecoveryAttemptKey key = ledger.documentCauseKey(causeKey);
    return ledger
        .remaining(load(runId).transitions(), key, InputOrigin.TRUSTED)
        .semanticRepairsRemaining();
  }

  private Result resultFrom(String runId, Defect request, RunTransition transition) {
    JsonNode document = JSON.valueToTree(documents.read(runId).document());
    String pointer = canonicalPointer(document, request.originRecordId(), request.fieldPointer());
    StoredCause stored = findByIdentity(document, request.originRecordId(), request.issueCategory(), pointer);
    if (stored == null) {
      stored = findById(document, request.findingId());
    }
    String origin = stored == null ? request.originRecordId() : stored.origin();
    String category = stored == null ? request.issueCategory() : stored.category();
    String fieldPointer = stored == null ? pointer : stored.pointer();
    String keyText = causeKey(runId, origin, category, fieldPointer);
    WorkStage owner = WorkStage.valueOf(transition.stageId());
    boolean dispatched =
        transition.reason() != null
            && transition.reason().startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX);
    String nextAction = dispatched ? "" : exhaustedAction(origin);
    return new Result(
        keyText,
        stored == null ? request.findingId() : stored.id(),
        owner,
        dispatched,
        !dispatched,
        nextAction,
        repairsRemaining(runId, keyText),
        null);
  }

  private boolean chargeTechnical(
      String runId, Throwable failure, String commandId, int executorLimit) {
    if (failure == null || !TransientFailures.isTransient(failure)) {
      throw rejected(
          "NOT_TECHNICAL",
          "The failure is not a connection retry. Submit a document finding, or retry the operation.");
    }
    ProductPipelineRunDocument current = load(runId);
    String payloadHash = sha256("technical" + FIELD + failure.getClass().getName());
    if (current.appliedCommand(commandId, payloadHash).isPresent()) {
      return true;
    }
    RecoveryAttemptKey key = ledger.technicalKey(runId);
    if (!ledger.mayTechnicalRetry(current.transitions(), key, executorLimit)) {
      return false;
    }
    String reason = ledger.recordTechnicalRetry(key);
    long expected = current.run().runRevision();
    Instant at = Instant.now();
    String stageId = current.run().currentStageId();
    runs.commit(
        expected,
        new LogicalCommit(
            runId,
            expected,
            current.run().status(),
            stageId,
            current.run().stages(),
            new StageAttempt(
                "work-" + commandId,
                stageId,
                expected + 1,
                StageStatus.RUNNING,
                at,
                at,
                List.of(),
                null,
                reason),
            new RunTransition(
                expected,
                expected + 1,
                current.run().status(),
                current.run().status(),
                stageId,
                at,
                reason,
                commandId,
                payloadHash)));
    return true;
  }

  private StoredCause resolveStored(JsonNode document, Defect request, String pointer) {
    StoredCause byId = findById(document, request.findingId());
    if (byId != null
        && (!byId.origin().equals(request.originRecordId())
            || !byId.category().equals(request.issueCategory())
            || !byId.pointer().equals(pointer))) {
      throw rejected(
          "SILENT_RENAME",
          "Finding "
              + byId.id()
              + " still has issue category "
              + byId.category()
              + ". Submit that category, or open a finding for a different requirement.");
    }
    if (byId != null) {
      return byId;
    }
    StoredCause byIdentity =
        findByIdentity(document, request.originRecordId(), request.issueCategory(), pointer);
    if (byIdentity != null) {
      return byIdentity;
    }
    if (request.originRecordId().isBlank() || request.issueCategory().isBlank()) {
      throw rejected(
          "UNKNOWN_RECORD",
          "A cause needs an origin record and an issue category. Name both from the document.");
    }
    String id =
        request.findingId().isBlank()
            ? "finding-" + request.originRecordId() + "-" + request.issueCategory() + "-" + pointer
            : request.findingId();
    return new StoredCause(id, request.originRecordId(), request.issueCategory(), pointer);
  }

  private static String upsertFinding(
      ObjectNode progress,
      StoredCause stored,
      String contradiction,
      List<String> evidenceIds,
      boolean updateWording) {
    ArrayNode findings = progress.withArray("findings");
    for (JsonNode finding : findings) {
      if (stored.id().equals(finding.path("id").asText())) {
        if (updateWording) {
          ((ObjectNode) finding).put("contradiction", contradiction);
        }
        return stored.id();
      }
    }
    ObjectNode created = findings.addObject();
    created.put("id", stored.id());
    created.put("recordRef", stored.origin());
    created.put("issueCategory", stored.category());
    created.put("contradiction", contradiction);
    created.put("canonicalFieldPointer", stored.pointer());
    ArrayNode evidence = created.putArray("evidenceIds");
    evidenceIds.forEach(evidence::add);
    return stored.id();
  }

  private static void markTasks(ObjectNode progress, WorkStage owner) {
    ArrayNode tasks = progress.withArray("tasks");
    List<String> later = laterStages(owner);
    boolean ownerSeen = false;
    for (JsonNode task : tasks) {
      ObjectNode node = (ObjectNode) task;
      String stage = node.path("stage").asText();
      if (owner.name().equals(stage)) {
        node.put("state", "PENDING");
        ownerSeen = true;
      } else if (later.contains(stage)) {
        node.put("state", "NEEDS_RECHECK");
      }
    }
    if (!ownerSeen) {
      ObjectNode task = tasks.addObject();
      task.put("taskId", "recovery-" + owner.name());
      task.put("state", "PENDING");
      task.put("stage", owner.name());
      task.put("skillId", skillId(owner));
    }
    ArrayNode recheck = JSON.createArrayNode();
    later.forEach(recheck::add);
    progress.set("recheckStages", recheck);
  }

  private static void addNextAction(ObjectNode progress, String origin, String action) {
    ArrayNode questions = progress.withArray("questions");
    for (JsonNode question : questions) {
      if ("NEXT_ACTION".equals(question.path("choice").asText())
          && origin.equals(question.path("id").asText("").replace("next-", ""))) {
        return;
      }
    }
    ObjectNode question = questions.addObject();
    question.put("id", "next-" + origin);
    question.put("choice", "NEXT_ACTION");
    question.put("question", action);
    question.putArray("evidenceIds");
  }

  private static RecoveryDecision businessDecision(
      Reference documentRef, String origin, WorkStage owner, boolean exhausted) {
    String failureId = "failure-" + origin;
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1,
            failureId,
            "DOCUMENT_DEFECT",
            owner.name(),
            "",
            null,
            null,
            documentRef == null ? List.of() : List.of(documentRef),
            List.of(),
            null,
            List.of(),
            List.of(origin));
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            exhausted ? null : documentRef,
            List.of(origin),
            exhausted ? RecoveryAction.PARK : RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            exhausted
                ? exhaustedAction(origin)
                : "Revise record " + origin + " and submit the correction.");
    accept(decision, evidence);
    return decision;
  }

  private static RecoveryDecision transportDecision(
      Reference documentRef, Throwable failure, boolean retry) {
    String failureId = "transport-" + (documentRef == null ? "run" : documentRef.artifactId());
    TechnicalFailureRecord technical =
        new TechnicalFailureRecord(
            true,
            1,
            "provider",
            "complete",
            "",
            "",
            failure == null ? "" : failure.getClass().getSimpleName(),
            failure == null || failure.getMessage() == null ? "" : failure.getMessage(),
            "",
            "");
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1,
            failureId,
            "TRANSPORT",
            "DATA_BEHAVIOR",
            "",
            null,
            null,
            documentRef == null ? List.of() : List.of(documentRef),
            List.of(),
            technical,
            List.of(),
            List.of());
    String summary =
        retry
            ? "The connection failed. The task will retry."
            : "Transport retries are spent. Retry the connection, then submit the task again.";
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.TECHNICAL_FAILURE,
            retry ? documentRef : null,
            List.of(failureId),
            retry ? RecoveryAction.RETRY_OPERATION : RecoveryAction.PARK,
            List.of(),
            "",
            summary);
    accept(decision, evidence);
    return decision;
  }

  private static void accept(RecoveryDecision decision, RecoveryEvidence evidence) {
    RecoveryDecisionValidator.Result result =
        RecoveryDecisionValidator.validate(
            decision, new RecoveryContext(evidence, null, null, "en"));
    if (!result.accepted()) {
      throw new IllegalStateException(String.join(" ", result.findings()));
    }
  }

  private static WorkStage ownerOf(JsonNode document, String recordId, String fieldPointer) {
    if (hasId(document.path("requirements"), recordId)
        || hasId(document.path("flow").path("connections"), recordId)
        || isGroup(document, recordId)) {
      return WorkStage.LOGICAL_FLOW;
    }
    JsonNode flow = document.path("flow");
    for (JsonNode step : flow.path("steps")) {
      if (recordId.equals(step.path("id").asText())) {
        if ("binding".equals(fieldPointer) || fieldPointer.startsWith("binding.")) {
          return WorkStage.SERVICES;
        }
        if ("data".equals(fieldPointer) || fieldPointer.startsWith("data.")) {
          return WorkStage.DATA_BEHAVIOR;
        }
        return WorkStage.LOGICAL_FLOW;
      }
      JsonNode data = step.path("data");
      for (JsonNode transfer : data.path("transfers")) {
        if (recordId.equals(transfer.path("id").asText())) {
          return WorkStage.DATA_BEHAVIOR;
        }
        for (JsonNode rule : transfer.path("rules")) {
          if (recordId.equals(rule.path("id").asText())) {
            return WorkStage.DATA_BEHAVIOR;
          }
        }
      }
      for (JsonNode retained : data.path("retainedValues")) {
        if (recordId.equals(retained.path("id").asText())) {
          return WorkStage.DATA_BEHAVIOR;
        }
      }
    }
    throw rejected(
        "UNKNOWN_RECORD",
        "Record " + recordId + " is not on the document. Name a record the document already stores.");
  }

  private static boolean knownRecord(JsonNode document, String recordId) {
    if (recordId == null || recordId.isBlank()) {
      return false;
    }
    try {
      ownerOf(document, recordId, "");
      return true;
    } catch (WorkDocumentRejectedException unknown) {
      return false;
    }
  }

  private static void requireEvidence(JsonNode document, List<String> evidenceIds) {
    for (String evidenceId : evidenceIds) {
      boolean source = false;
      for (JsonNode sourceNode : document.path("sources")) {
        if (evidenceId.equals(sourceNode.path("id").asText())) {
          source = true;
          break;
        }
      }
      if (!source && !knownRecord(document, evidenceId)) {
        throw rejected(
            "UNKNOWN_EVIDENCE",
            "Evidence "
                + evidenceId
                + " is not on the document. Cite a stored source or record.");
      }
    }
  }

  private static String canonicalPointer(JsonNode document, String recordId, String fieldPointer) {
    if ((fieldPointer == null || fieldPointer.isBlank()) && isGroup(document, recordId)) {
      return recordId;
    }
    return fieldPointer == null ? "" : fieldPointer;
  }

  private static boolean isGroup(JsonNode document, String recordId) {
    JsonNode flow = document.path("flow");
    return hasId(flow.path("sequenceGroups"), recordId)
        || hasId(flow.path("conditionGroups"), recordId)
        || hasId(flow.path("splitGroups"), recordId)
        || hasId(flow.path("loopGroups"), recordId)
        || hasId(flow.path("retryGroups"), recordId)
        || hasId(flow.path("errorScopeGroups"), recordId);
  }

  private static boolean hasId(JsonNode records, String recordId) {
    if (records == null || !records.isArray()) {
      return false;
    }
    for (JsonNode record : records) {
      if (recordId.equals(record.path("id").asText())) {
        return true;
      }
    }
    return false;
  }

  private static StoredCause findById(JsonNode document, String findingId) {
    if (findingId == null || findingId.isBlank()) {
      return null;
    }
    for (JsonNode finding : document.path("progress").path("findings")) {
      if (findingId.equals(finding.path("id").asText())) {
        return stored(finding);
      }
    }
    return null;
  }

  private static StoredCause findByIdentity(
      JsonNode document, String origin, String category, String pointer) {
    for (JsonNode finding : document.path("progress").path("findings")) {
      StoredCause stored = stored(finding);
      if (stored.origin().equals(origin)
          && stored.category().equals(category)
          && stored.pointer().equals(pointer)) {
        return stored;
      }
    }
    return null;
  }

  private static StoredCause stored(JsonNode finding) {
    return new StoredCause(
        finding.path("id").asText(),
        finding.path("recordRef").asText(),
        finding.path("issueCategory").asText(),
        finding.path("canonicalFieldPointer").asText());
  }

  private static List<String> laterStages(WorkStage owner) {
    List<String> later = new ArrayList<>();
    for (WorkStage stage : WorkStage.values()) {
      if (stage.ordinal() > owner.ordinal()) {
        later.add(stage.name());
      }
    }
    return later;
  }

  private static String skillId(WorkStage stage) {
    return switch (stage) {
      case LOGICAL_FLOW -> "logical-design";
      case SERVICES -> "operation-selection";
      case DATA_BEHAVIOR -> "data-mapping";
      default -> "";
    };
  }

  private static String exhaustedAction(String origin) {
    return "Corrective attempts for this cause are spent. Revise record "
        + origin
        + " and submit the correction.";
  }

  private static RecoveryAttemptLedger documentLedger() {
    return new RecoveryAttemptLedger(
        new RecoveryAttemptLedger.Limits(
            RecoveryAttemptLedger.DOCUMENT_CORRECTIVE_LIMIT,
            ProductPipelineRunSupport.MAX_CAUSAL_REOPENS,
            RecoveryAttemptLedger.DEFAULT_PER_RUN_CEILING));
  }

  private static String payloadHash(String kind, Defect defect) {
    return sha256(
        kind
            + FIELD
            + defect.findingId()
            + FIELD
            + defect.originRecordId()
            + FIELD
            + defect.issueCategory()
            + FIELD
            + defect.fieldPointer()
            + FIELD
            + defect.contradiction()
            + FIELD
            + String.join(",", defect.evidenceIds())
            + FIELD
            + defect.revealedRecordId()
            + FIELD
            + defect.revealedFieldPointer());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static void requireCommand(String commandId) {
    if (commandId == null || commandId.isBlank()) {
      throw rejected(
          "MISSING_COMMAND", "A command id is required. Reuse it to replay this delivery.");
    }
  }

  private ProductPipelineRunDocument load(String runId) {
    return runs.load(runId)
        .orElseThrow(
            () ->
                rejected(
                    "UNKNOWN_RECORD",
                    "Run " + runId + " was not found. Start the run before routing recovery."));
  }

  private static WorkDocumentRejectedException rejected(String code, String message) {
    return new WorkDocumentRejectedException(code, message);
  }

  private record StoredCause(String id, String origin, String category, String pointer) {}

  public record Defect(
      String findingId,
      String originRecordId,
      String issueCategory,
      String fieldPointer,
      String contradiction,
      List<String> evidenceIds,
      String revealedRecordId,
      String revealedFieldPointer) {

    public Defect {
      findingId = findingId == null ? "" : findingId;
      originRecordId = originRecordId == null ? "" : originRecordId;
      issueCategory = issueCategory == null ? "" : issueCategory;
      fieldPointer = fieldPointer == null ? "" : fieldPointer;
      contradiction = contradiction == null ? "" : contradiction;
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
      revealedRecordId = revealedRecordId == null ? "" : revealedRecordId;
      revealedFieldPointer = revealedFieldPointer == null ? "" : revealedFieldPointer;
    }

    public static Defect of(
        String originRecordId,
        String issueCategory,
        String fieldPointer,
        String contradiction,
        String evidenceId) {
      return new Defect(
          "",
          originRecordId,
          issueCategory,
          fieldPointer,
          contradiction,
          List.of(evidenceId),
          "",
          "");
    }
  }

  public record Result(
      String causeKey,
      String findingId,
      WorkStage owner,
      boolean dispatched,
      boolean exhausted,
      String nextAction,
      int repairsRemaining,
      RecoveryDecision decision) {}
}
