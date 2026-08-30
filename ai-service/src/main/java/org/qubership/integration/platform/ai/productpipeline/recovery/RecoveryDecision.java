package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Structured recovery decision returned by the failure narrative agent. */
public record RecoveryDecision(
    RecoveryCauseClass causeClass,
    Reference faultArtifactRef,
    List<String> evidenceRefs,
    RecoveryAction action,
    List<ProposedBriefChange> proposedBriefChanges,
    String question,
    String userSummary) {

  public RecoveryDecision {
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    proposedBriefChanges =
        proposedBriefChanges == null ? List.of() : List.copyOf(proposedBriefChanges);
  }
}
