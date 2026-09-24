package org.qubership.integration.platform.ai.plan.workdocument.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkOutcome;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskCapture;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Runs one server-owned document task. The capability runner and compiler scheduler call
 * {@link #execute}. The model cannot widen the scope or choose the runtime stage.
 */
public final class WorkTaskExecutor {

  private final WorkDocumentService documents;
  private final ProductPipelineRunStore runs;
  private final Clock clock;
  private final ObjectMapper json = new ObjectMapper();

  public WorkTaskExecutor(WorkDocumentService documents, ProductPipelineRunStore runs, Clock clock) {
    this.documents = documents;
    this.runs = runs;
    this.clock = clock;
  }

  public WorkCommit execute(
      String runId, WorkTaskScope scope, WorkTaskMaterials materials, WorkTaskModel model) {
    String invocationId = scope.taskId() + ":" + scope.baseRevision();
    ProductPipelineRunDocument current = load(runId);
    if (published(current, invocationId)) {
      return committed(current, invocationId);
    }
    WorkDocumentState state = documents.read(runId);
    if (!scope.baseRevision().equals(state.revision())) {
      throw new WorkDocumentRejectedException(
          "STALE_SCOPE",
          "Scope revision does not match the current document. Read the document and submit the task again.");
    }
    recordInvocation(current, invocationId);
    String output = model.complete(WorkTaskContext.prompt(state, scope, materials));
    WorkTaskCapture capture = parse(output);
    return documents.apply(runId, scope, capture, invocationId);
  }

  private void recordInvocation(ProductPipelineRunDocument current, String invocationId) {
    String startCommand = invocationId + "#start";
    if (current.transitions().stream().anyMatch(transition -> startCommand.equals(transition.commandId()))) {
      return;
    }
    long expected = current.run().runRevision();
    Instant at = clock.instant();
    String stageId = current.run().currentStageId();
    runs.commit(
        expected,
        new LogicalCommit(
            current.run().runId(),
            expected,
            current.run().status(),
            stageId,
            current.run().stages(),
            new StageAttempt(
                invocationId,
                stageId,
                expected + 1L,
                StageStatus.RUNNING,
                at,
                at,
                List.of(),
                null,
                invocationId),
            new RunTransition(
                expected,
                expected + 1L,
                current.run().status(),
                current.run().status(),
                stageId,
                at,
                "work-task-invocation",
                startCommand,
                "started")));
  }

  private WorkCommit committed(ProductPipelineRunDocument current, String invocationId) {
    StageAttempt attempt =
        current.attempts().stream()
            .filter(candidate -> ("work-" + invocationId).equals(candidate.attemptId()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Published invocation has no matching attempt."));
    RunTransition transition =
        current.transitions().stream()
            .filter(candidate -> invocationId.equals(candidate.commandId()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Published invocation has no matching transition."));
    WorkDocumentState state = documents.read(current.run().runId());
    attempt
        .outputs()
        .stream()
        .filter(output -> output.kind() == Kind.CHAIN_WORK_DOCUMENT)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("Published invocation has no work document."));
    JsonNode receipt = readReceipt(attempt.commandReceipt());
    return new WorkCommit(
        state.revision(),
        textList(receipt.path("acceptedRecordIds")),
        WorkOutcome.valueOf(receipt.path("outcome").asText()),
        invocationId,
        state,
        textMap(receipt.path("aliasToId")),
        transition.toRevision());
  }

  private static boolean published(ProductPipelineRunDocument current, String invocationId) {
    return current.transitions().stream()
        .anyMatch(transition -> invocationId.equals(transition.commandId()));
  }

  private WorkTaskCapture parse(String output) {
    if (output == null || output.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE",
          "Model output is empty. Send one capture object for this task.");
    }
    JsonNode tree;
    try {
      tree = json.readTree(output);
    } catch (Exception failure) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE",
          "Model output is not a capture object. The task was not completed.");
    }
    if (!tree.isObject() || tree.path("outcome").asText().isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE",
          "Model output has no outcome. The task was not completed.");
    }
    if (tree.has("stage") || tree.has("targetStage") || tree.has("runtimeStage")) {
      throw new WorkDocumentRejectedException(
          "SERVER_OWNED_FIELD",
          "Capture property stage is server-owned. Name the record and the evidence, and remove the stage.");
    }
    return WorkDocumentCaptureSchema.parse(output);
  }

  private JsonNode readReceipt(String commandReceipt) {
    try {
      return json.readTree(commandReceipt);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot read the committed command receipt.", failure);
    }
  }

  private static List<String> textList(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    return node.valueStream().map(JsonNode::asText).toList();
  }

  private static Map<String, String> textMap(JsonNode node) {
    if (!node.isObject()) {
      return Map.of();
    }
    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    node.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue().asText()));
    return Map.copyOf(values);
  }

  private ProductPipelineRunDocument load(String runId) {
    return runs.load(runId)
        .orElseThrow(() -> new IllegalArgumentException("Run was not found: " + runId));
  }
}
