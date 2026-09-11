package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Durable, lossless recovery evidence for one failure observation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecoveryEvidence(
    int schemaVersion,
    String failureId,
    String observedCauseCode,
    String observingStageId,
    String producerStageId,
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
    producerStageId = producerStageId == null ? "" : producerStageId;
    rejectedArtifactRefs =
        rejectedArtifactRefs == null ? List.of() : List.copyOf(rejectedArtifactRefs);
    findings = findings == null ? List.of() : List.copyOf(findings);
    priorAttemptRefs = priorAttemptRefs == null ? List.of() : List.copyOf(priorAttemptRefs);
  }

  /**
   * Legacy ten-argument construction. Producer stays empty so old callers and payloads remain
   * readable.
   */
  public RecoveryEvidence(
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
    this(
        schemaVersion,
        failureId,
        observedCauseCode,
        observingStageId,
        "",
        approvedBriefRef,
        approvedSemanticRef,
        rejectedArtifactRefs,
        findings,
        technicalFailure,
        priorAttemptRefs);
  }
}
