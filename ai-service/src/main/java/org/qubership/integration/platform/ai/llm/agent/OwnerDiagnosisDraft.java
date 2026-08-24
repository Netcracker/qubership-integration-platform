package org.qubership.integration.platform.ai.llm.agent;

/**
 * Structured reply from the diagnosis turn. {@code ownerStageId} must be empty or a stage id from
 * the candidate set the prompt listed; {@code ambiguous} is true when two of those candidates stay
 * plausible.
 */
public record OwnerDiagnosisDraft(String narrative, String ownerStageId, boolean ambiguous) {

  public OwnerDiagnosisDraft {
    narrative = narrative == null ? "" : narrative;
    ownerStageId = ownerStageId == null ? "" : ownerStageId;
  }
}
