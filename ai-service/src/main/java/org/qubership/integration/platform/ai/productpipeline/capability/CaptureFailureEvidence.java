package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;

/** Native capture rejection and its consequences; neither grants amendment authority. */
public record CaptureFailureEvidence(CaptureAttemptFeedback feedback, Reference acceptedBase,
    Reference rejectedCandidate, List<PlanValidationFinding> consequentialFindings,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore.Observation nativeObservation) {
  public CaptureFailureEvidence(CaptureAttemptFeedback feedback, Reference acceptedBase,
      Reference rejectedCandidate, List<PlanValidationFinding> consequentialFindings) {
    this(feedback, acceptedBase, rejectedCandidate, consequentialFindings, null);
  }

  public CaptureFailureEvidence {
    consequentialFindings = consequentialFindings == null ? List.of() : List.copyOf(consequentialFindings);
  }
}
