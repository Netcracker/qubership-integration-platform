package org.qubership.integration.platform.ai.productpipeline.store;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;

/** Authoritative mutable-facing run snapshot persisted inside the CAS run document. */
public record RunSnapshot(
    String runId,
    String conversationId,
    long runRevision,
    RunStatus status,
    String currentStageId,
    List<StageSnapshot> stages,
    CompilationArtifacts.Reference runManifestRef,
    String flowInstanceId,
    CompilationArtifacts.Reference workDocumentRef,
    WorkRepairBudget workRepairBudget) {

  public RunSnapshot {
    stages = stages == null ? List.of() : List.copyOf(stages);
    if (flowInstanceId != null && flowInstanceId.isBlank()) {
      flowInstanceId = null;
    }
  }

  /** Compatibility constructor for documents that predate the work-document pointer. */
  public RunSnapshot(
      String runId,
      String conversationId,
      long runRevision,
      RunStatus status,
      String currentStageId,
      List<StageSnapshot> stages,
      CompilationArtifacts.Reference runManifestRef,
      String flowInstanceId) {
    this(
        runId,
        conversationId,
        runRevision,
        status,
        currentStageId,
        stages,
        runManifestRef,
        flowInstanceId,
        null,
        null);
  }

  /** Compatibility constructor for documents that predate Flow instance association. */
  public RunSnapshot(
      String runId,
      String conversationId,
      long runRevision,
      RunStatus status,
      String currentStageId,
      List<StageSnapshot> stages,
      CompilationArtifacts.Reference runManifestRef) {
    this(
        runId,
        conversationId,
        runRevision,
        status,
        currentStageId,
        stages,
        runManifestRef,
        null);
  }
}
