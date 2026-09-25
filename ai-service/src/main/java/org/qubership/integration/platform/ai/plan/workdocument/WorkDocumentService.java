package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.store.CommandPayloadConflictException;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.plan.workdocument.source.SourcePassageIndexer;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/** In-memory editor plus prepare-then-publish onto the run CAS. */
public final class WorkDocumentService {

  private static final String SCHEMA_VERSION = "2";
  private static final String PRODUCER_ID = "work-document";
  private static final String INPUT_PRODUCER = "work-document-input";

  private final WorkDocumentEditor editor = new WorkDocumentEditor();
  private final ProductPipelineRunStore runs;
  private final CompilationArtifacts artifacts;
  private final ObjectMapper json;
  private final Clock clock;

  public WorkDocumentService() {
    this(null, null, null);
  }

  public WorkDocumentService(
      ProductPipelineRunStore runs, CompilationArtifacts artifacts, ObjectMapper json) {
    this.runs = runs;
    this.artifacts = artifacts;
    this.json =
        (json == null ? new ObjectMapper() : json)
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.clock = Clock.systemUTC();
  }

  public WorkDocumentState read(WorkDocumentState state) {
    return state;
  }

  public WorkDocumentState read(String runId) {
    requireStore();
    ProductPipelineRunDocument document = load(runId);
    Reference reference = document.run().workDocumentRef();
    if (reference == null) {
      throw new IllegalArgumentException("Run has no work document.");
    }
    return stateOf(runId, reference);
  }

  public WorkCommit apply(
      WorkDocumentState state, WorkTaskScope taskScope, WorkTaskCapture capture, String commandId) {
    return editor.apply(state, taskScope, capture, commandId);
  }

  public WorkCommit apply(
      String runId, WorkTaskScope taskScope, WorkTaskCapture capture, String commandId) {
    return apply(runId, taskScope, capture, commandId, null);
  }

  public WorkCommit apply(
      String runId,
      WorkTaskScope taskScope,
      WorkTaskCapture capture,
      String commandId,
      WorkRepairBudget repairBudget) {
    requireStore();
    Objects.requireNonNull(taskScope, "taskScope");
    Objects.requireNonNull(capture, "capture");
    ProductPipelineRunDocument current = load(runId);
    String payloadHash = payloadHash(capture, repairBudget);
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return committedResult(current, replay.get());
    }
    WorkDocumentState state = read(runId);
    WorkCommit edited;
    try {
      edited = editor.apply(state, taskScope, capture, commandId);
    } catch (WorkDocumentRejectedException rejected) {
      recordRejectedAttempt(current, commandId, rejected);
      throw rejected;
    }
    return publish(
        current,
        edited.state(),
        edited,
        commandId,
        payloadHash,
        repairBudget,
        "work-document",
        current.run().currentStageId());
  }

  public WorkCommit intake(
      String runId, WorkDocumentState state, String commandId, WorkRepairBudget repairBudget) {
    requireStore();
    Objects.requireNonNull(state, "state");
    ProductPipelineRunDocument current = load(runId);
    String payloadHash = payloadHash(state.revision(), repairBudget);
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return committedResult(current, replay.get());
    }
    if (current.run().workDocumentRef() != null) {
      throw new IllegalArgumentException("Run already has a work document.");
    }
    WorkCommit edited =
        new WorkCommit(
            state.revision(), List.of(), WorkOutcome.PREPARED, commandId, state, Map.of());
    return publish(
        current,
        state,
        edited,
        commandId,
        payloadHash,
        repairBudget,
        "work-document",
        current.run().currentStageId());
  }

  public WorkCommit intake(
      String runId,
      WorkDocumentState state,
      String commandId,
      WorkRepairBudget repairBudget,
      String payloadHash,
      List<String> acceptedRecordIds) {
    requireStore();
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadHash, "payloadHash");
    ProductPipelineRunDocument current = load(runId);
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return committedResult(current, replay.get());
    }
    WorkCommit edited =
        new WorkCommit(
            state.revision(),
            acceptedRecordIds == null ? List.of() : List.copyOf(acceptedRecordIds),
            WorkOutcome.PREPARED,
            commandId,
            state,
            Map.of());
    return publish(
        current,
        state,
        edited,
        commandId,
        payloadHash,
        repairBudget,
        "work-document",
        current.run().currentStageId());
  }

  /**
   * Publishes a recovered document and the ledger charge in one run commit. A repeated command
   * returns the committed result and does not charge again.
   */
  public WorkCommit commitRecoveredDocument(
      String runId,
      ChainWorkDocument document,
      String commandId,
      String payloadHash,
      String transitionReason,
      String stageId,
      WorkRepairBudget repairBudget) {
    requireStore();
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(payloadHash, "payloadHash");
    Objects.requireNonNull(transitionReason, "transitionReason");
    ProductPipelineRunDocument current = load(runId);
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return committedResult(current, replay.get());
    }
    WorkDocumentState state = WorkDocumentState.of(document);
    WorkCommit edited =
        new WorkCommit(
            state.revision(), List.of(), WorkOutcome.PREPARED, commandId, state, Map.of());
    return publish(
        current, state, edited, commandId, payloadHash, repairBudget, transitionReason, stageId);
  }

  public WorkCommit applyOutline(
      WorkDocumentState state, WorkTaskScope scope, OutlineProposal proposal, String commandId) {
    return editor.applyOutline(state, scope, proposal, commandId);
  }

  /**
   * Publishes one outline revision. A repeated command with the same proposal returns the stored
   * commit.
   */
  public WorkCommit applyOutline(
      String runId, WorkTaskScope scope, OutlineProposal proposal, String commandId) {
    requireStore();
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(proposal, "proposal");
    ProductPipelineRunDocument current = load(runId);
    String payloadHash = sha256(write(Map.of("outline", proposal, "task", scope.taskKey())));
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return committedResult(current, replay.get());
    }
    WorkDocumentState state = read(runId);
    WorkCommit edited;
    try {
      edited = editor.applyOutline(state, scope, proposal, commandId);
    } catch (WorkDocumentRejectedException rejected) {
      recordRejectedAttempt(current, commandId, rejected);
      throw rejected;
    }
    return publish(
        current,
        edited.state(),
        edited,
        commandId,
        payloadHash,
        null,
        "work-document",
        current.run().currentStageId());
  }

  public WorkCommit recordQuestion(
      String runId,
      WorkTaskScope scope,
      String questionText,
      QuestionSubject subject,
      List<String> blockedRecordIds,
      List<String> evidenceIds,
      String commandId) {
    requireStore();
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(subject, "subject");
    ProductPipelineRunDocument current = load(runId);
    String payloadHash =
        sha256(
            write(
                Map.of(
                    "question", questionText == null ? "" : questionText,
                    "subject", subject,
                    "blocked", blockedRecordIds == null ? List.of() : blockedRecordIds,
                    "evidence", evidenceIds == null ? List.of() : evidenceIds)));
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return committedResult(current, replay.get());
    }
    WorkDocumentState state = read(runId);
    WorkCommit edited =
        editor.recordQuestion(
            state,
            scope,
            questionText,
            subject,
            blockedRecordIds == null ? List.of() : blockedRecordIds,
            evidenceIds == null ? List.of() : evidenceIds,
            commandId);
    return publish(
        current,
        edited.state(),
        edited,
        commandId,
        payloadHash,
        null,
        "work-document",
        current.run().currentStageId());
  }

  public WorkCommit acceptInput(String runId, String questionId, String inputId, String text) {
    requireStore();
    if (inputId == null || inputId.isBlank()) {
      throw new IllegalArgumentException("inputId is required");
    }
    String body = text == null ? "" : text;
    String commandId = "input:" + runId + ":" + inputId;
    String payloadHash = sha256(body.getBytes(StandardCharsets.UTF_8));
    ProductPipelineRunDocument current = load(runId);
    Optional<RunTransition> replay = current.appliedCommand(commandId, payloadHash);
    if (replay.isPresent()) {
      return committedResult(current, replay.get());
    }
    StoredInput existing = findInput(runId, inputId);
    if (existing != null && !existing.contentHash.equals(payloadHash)) {
      throw new CommandPayloadConflictException(commandId, existing.contentHash, payloadHash);
    }
    String contentReference;
    if (existing == null) {
      Revision stored =
          artifacts.append(
              new AppendCommand(
                  runId,
                  Kind.USER_INPUT,
                  "1",
                  INPUT_PRODUCER,
                  "1",
                  new InputReceipt(runId, inputId, payloadHash, body),
                  List.of(),
                  null));
      contentReference = stored.reference().artifactId();
    } else {
      contentReference = existing.contentReference;
    }
    WorkDocumentState state = read(runId);
    WorkCommit edited =
        editor.linkInput(state, questionId, inputId, body, payloadHash, contentReference, commandId);
    return publish(
        current,
        edited.state(),
        edited,
        commandId,
        payloadHash,
        null,
        "work-document-input",
        current.run().currentStageId());
  }

  public WorkDocumentState addPassages(
      WorkDocumentState state, String sourceId, List<SourcePassage> passages) {
    return editor.addPassages(state, sourceId, passages);
  }

  /** Indexes sources that store text and do not yet have passages. Existing passages stay put. */
  public WorkDocumentState indexSourcePassages(WorkDocumentState state) {
    WorkDocumentState current = WorkDocumentState.of(state.document());
    for (WorkSource source : List.copyOf(current.document().sources())) {
      if (source.content().isBlank() || !source.passages().isEmpty()) {
        continue;
      }
      List<SourcePassage> passages = SourcePassageIndexer.index(source.id(), source.content());
      if (!passages.isEmpty()) {
        current = editor.addPassages(current, source.id(), passages);
      }
    }
    return current;
  }

  public WorkSource passageSource(WorkDocumentState state, String passageId) {
    return editor.passageSource(state, passageId);
  }

  public WorkDocumentState appendSource(WorkDocumentState state, WorkSource source) {
    return editor.appendSource(state, source);
  }

  public WorkDocumentState attachResolvedBinding(
      WorkDocumentState state, String stepId, ResolvedWorkBinding binding) {
    return editor.attachResolvedBinding(state, stepId, binding);
  }

  private WorkCommit publish(
      ProductPipelineRunDocument current,
      WorkDocumentState state,
      WorkCommit edited,
      String commandId,
      String payloadHash,
      WorkRepairBudget repairBudget,
      String transitionReason,
      String stageId) {
    Reference previous = current.run().workDocumentRef();
    Revision stored =
        artifacts.append(
            new AppendCommand(
                current.run().runId(),
                Kind.CHAIN_WORK_DOCUMENT,
                SCHEMA_VERSION,
                PRODUCER_ID,
                SCHEMA_VERSION,
                state.document(),
                List.of(),
                previous == null ? null : previous.artifactId()));
    long expected = current.run().runRevision();
    long next = expected + 1L;
    StageStatus stageStatus = stageStatus(edited.outcome());
    RunStatus runStatus = runStatus(edited.outcome());
    String publishedStage = stageId == null || stageId.isBlank() ? current.run().currentStageId() : stageId;
    Instant at = clock.instant();
    Reference reference = stored.reference();
    LogicalCommit commit =
        new LogicalCommit(
            current.run().runId(),
            expected,
            runStatus,
            publishedStage,
            stagesWithPublication(current, publishedStage, stageStatus, reference),
            new StageAttempt(
                "work-" + commandId,
                publishedStage,
                next,
                stageStatus,
                at,
                at,
                List.of(reference),
                null,
                receipt(edited)),
            new RunTransition(
                expected,
                next,
                current.run().status(),
                runStatus,
                publishedStage,
                at,
                transitionReason,
                commandId,
                payloadHash),
            reference,
            repairBudget);
    ProductPipelineRunDocument committed = runs.commit(expected, commit);
    return new WorkCommit(
        edited.documentRevision(),
        edited.acceptedRecordIds(),
        edited.outcome(),
        edited.commandId(),
        edited.state(),
        edited.aliasToId(),
        committed.run().runRevision());
  }

  public WorkCommit committedResult(ProductPipelineRunDocument document, RunTransition transition) {
    StageAttempt attempt =
        document.attempts().stream()
            .filter(candidate -> candidate.runRevision() == transition.toRevision())
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Committed command has no matching attempt."));
    Reference reference =
        attempt.outputs().stream()
            .filter(output -> output.kind() == Kind.CHAIN_WORK_DOCUMENT)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Committed command has no work document."));
    WorkDocumentState state = stateOf(document.run().runId(), reference);
    CommandReceipt receipt = readReceipt(attempt.commandReceipt());
    return new WorkCommit(
        state.revision(),
        receipt.acceptedRecordIds(),
        receipt.outcome(),
        transition.commandId(),
        state,
        receipt.aliasToId(),
        transition.toRevision());
  }

  private static List<StageSnapshot> stagesWithPublication(
      ProductPipelineRunDocument current,
      String stageId,
      StageStatus stageStatus,
      Reference reference) {
    List<StageSnapshot> nextStages = new ArrayList<>();
    boolean published = false;
    for (StageSnapshot snapshot : current.run().stages()) {
      if (snapshot.stageId().equals(stageId)) {
        nextStages.add(
            new StageSnapshot(
                snapshot.stageId(),
                stageStatus,
                List.of(reference),
                snapshot.approvedArtifactId(),
                snapshot.candidateReferences(),
                snapshot.approvableReference(),
                snapshot.candidateRevision()));
        published = true;
      } else {
        nextStages.add(snapshot);
      }
    }
    if (!published) {
      nextStages.add(new StageSnapshot(stageId, stageStatus, List.of(reference), null));
    }
    return nextStages;
  }

  private String receipt(WorkCommit edited) {
    return new String(
        write(
            new CommandReceipt(
                edited.acceptedRecordIds(), edited.aliasToId(), edited.outcome())),
        StandardCharsets.UTF_8);
  }

  private CommandReceipt readReceipt(String commandReceipt) {
    if (commandReceipt == null || commandReceipt.isBlank()) {
      throw new IllegalStateException("Committed command has no receipt.");
    }
    try {
      return json.readValue(commandReceipt, CommandReceipt.class);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot read the committed command receipt.", failure);
    }
  }

  private record CommandReceipt(
      List<String> acceptedRecordIds, Map<String, String> aliasToId, WorkOutcome outcome) {}

  private WorkDocumentState stateOf(String runId, Reference reference) {
    Revision revision =
        artifacts
            .get(runId, reference)
            .orElseThrow(() -> new IllegalArgumentException("Work document artifact was not found."));
    return WorkDocumentState.of(artifacts.payload(revision, ChainWorkDocument.class));
  }

  private static StageStatus stageStatus(WorkOutcome outcome) {
    return switch (outcome) {
      case NEEDS_CLARIFICATION -> StageStatus.WAITING_FOR_INPUT;
      case INPUT_DEFECT -> StageStatus.FAILED;
      case PREPARED -> StageStatus.SUCCEEDED;
    };
  }

  private static RunStatus runStatus(WorkOutcome outcome) {
    return outcome == WorkOutcome.NEEDS_CLARIFICATION
        ? RunStatus.WAITING_FOR_INPUT
        : RunStatus.RUNNING;
  }

  private ProductPipelineRunDocument load(String runId) {
    return runs
        .load(runId)
        .orElseThrow(() -> new IllegalArgumentException("Run was not found: " + runId));
  }

  private void requireStore() {
    if (runs == null || artifacts == null) {
      throw new IllegalStateException("Work document storage is not configured.");
    }
  }

  private String payloadHash(WorkTaskCapture capture, WorkRepairBudget repairBudget) {
    return sha256(write(Map.of("capture", capture, "repairsRemaining", remaining(repairBudget))));
  }

  private String payloadHash(String documentRevision, WorkRepairBudget repairBudget) {
    return sha256(
        write(Map.of("documentRevision", documentRevision, "repairsRemaining", remaining(repairBudget))));
  }

  private static int remaining(WorkRepairBudget repairBudget) {
    return repairBudget == null ? -1 : repairBudget.repairsRemaining();
  }

  private byte[] write(Object value) {
    try {
      return json.writeValueAsBytes(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot hash the work-document command.", failure);
    }
  }

  private void recordRejectedAttempt(
      ProductPipelineRunDocument current, String commandId, WorkDocumentRejectedException rejected) {
    long expected = current.run().runRevision();
    long next = expected + 1L;
    Instant at = clock.instant();
    String stage = current.run().currentStageId();
    try {
      runs.commit(
          expected,
          new LogicalCommit(
              current.run().runId(),
              expected,
              current.run().status(),
              stage,
              current.run().stages(),
              new StageAttempt(
                  "rejected-" + commandId,
                  stage,
                  next,
                  StageStatus.FAILED,
                  at,
                  at,
                  List.of(),
                  rejected.code(),
                  null),
              new RunTransition(
                  expected,
                  next,
                  current.run().status(),
                  current.run().status(),
                  stage,
                  at,
                  "rejected-attempt:" + rejected.code() + ":" + commandId,
                  null,
                  null),
              null,
              null));
    } catch (RuntimeException failure) {
      rejected.addSuppressed(failure);
    }
  }

  private StoredInput findInput(String runId, String inputId) {
    for (Revision revision : artifacts.history(runId, Kind.USER_INPUT)) {
      if (!INPUT_PRODUCER.equals(revision.producerId())) {
        continue;
      }
      InputReceipt receipt = artifacts.payload(revision, InputReceipt.class);
      if (receipt != null && inputId.equals(receipt.inputId())) {
        return new StoredInput(receipt.contentHash(), revision.reference().artifactId());
      }
    }
    return null;
  }

  private record StoredInput(String contentHash, String contentReference) {}

  public record InputReceipt(String runId, String inputId, String contentHash, String text) {}

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }
}
