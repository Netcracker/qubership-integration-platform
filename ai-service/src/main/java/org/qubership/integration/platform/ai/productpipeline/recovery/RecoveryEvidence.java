package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Durable, lossless recovery evidence for one failure observation. */
public record RecoveryEvidence(
    int schemaVersion,
    String failureId,
    String observedCauseCode,
    String observingStageId,
    Reference approvedBriefRef,
    Reference approvedSemanticRef,
    List<Reference> rejectedArtifactRefs,
    List<SemanticFinding> findings,
    TechnicalFailureRecord technicalFailure,
    List<Reference> priorAttemptRefs) {

  public RecoveryEvidence {
    if (schemaVersion != 1) {
      throw new IllegalArgumentException("schemaVersion must be 1");
    }
    if (failureId == null || failureId.isBlank()) {
      throw new IllegalArgumentException("failureId is required");
    }
    rejectedArtifactRefs =
        rejectedArtifactRefs == null ? List.of() : List.copyOf(rejectedArtifactRefs);
    findings = findings == null ? List.of() : List.copyOf(findings);
    priorAttemptRefs = priorAttemptRefs == null ? List.of() : List.copyOf(priorAttemptRefs);
  }
}
