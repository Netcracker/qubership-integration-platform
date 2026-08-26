package org.qubership.integration.platform.ai.llm.agent;

/**
 * Structured reply from the diagnosis turn. {@code ownerStageId} must be empty or a stage id from
 * the candidate set the prompt listed; {@code ambiguous} is true when two of those candidates stay
 * plausible.
 *
 * <p>{@code remedy} and {@code instruction} carry the change that would clear the halt: a token
 * from the closed remedy set, and one sentence in the pinned response locale naming what to add,
 * remove, or correct. Both stay raw strings here, the way {@code ownerStageId} does, so the runtime
 * can drop a token it does not recognize instead of failing the whole turn over it.
 */
public record OwnerDiagnosisDraft(
    String narrative, String ownerStageId, boolean ambiguous, String remedy, String instruction) {

  public OwnerDiagnosisDraft {
    narrative = narrative == null ? "" : narrative;
    ownerStageId = ownerStageId == null ? "" : ownerStageId;
    remedy = remedy == null ? "" : remedy;
    instruction = instruction == null ? "" : instruction;
  }
}
