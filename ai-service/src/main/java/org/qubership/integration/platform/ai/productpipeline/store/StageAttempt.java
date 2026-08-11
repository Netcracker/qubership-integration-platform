package org.qubership.integration.platform.ai.productpipeline.store;

import java.time.Instant;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Append-only record of one stage execution attempt. */
public record StageAttempt(
    String attemptId,
    String stageId,
    long runRevision,
    StageStatus outcome,
    Instant startedAt,
    Instant finishedAt,
    List<CompilationArtifacts.Reference> outputs,
    String failureEvidence) {

  public StageAttempt {
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }
}
