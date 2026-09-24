package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
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
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/** In-memory editor plus prepare-then-publish onto the run CAS. */
public final class WorkDocumentService {

  private static final String SCHEMA_VERSION = "1";
  private static final String PRODUCER_ID = "work-document";

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
    WorkCommit edited = editor.apply(state, taskScope, capture, commandId);
    return publish(current, edited.state(), edited, commandId, payloadHash, repairBudget);
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
    return publish(current, state, edited, commandId, payloadHash, repairBudget);
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
      WorkRepairBudget repairBudget) {
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
    String stageId = current.run().currentStageId();
    Instant at = clock.instant();
    Reference reference = stored.reference();
    LogicalCommit commit =
        new LogicalCommit(
            current.run().runId(),
            expected,
            runStatus,
            stageId,
            List.of(new StageSnapshot(stageId, stageStatus, List.of(reference), null)),
            new StageAttempt(
                "work-" + commandId,
                stageId,
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
                stageId,
                at,
                "work-document",
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

  private WorkCommit committedResult(ProductPipelineRunDocument document, RunTransition transition) {
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

  private String receipt(WorkCommit edited) {
    return new String(
        write(
            new CommandReceipt(
                edited.acceptedRecordIds(), edited.aliasToId(), edited.outcome())),
        java.nio.charset.StandardCharsets.UTF_8);
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

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }
}
