package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.Optional;

/**
 * Result of one owner-diagnosis turn: model-authored narrative plus the owner the router selected
 * from the closed candidate set, or an ask when more than one candidate stays plausible.
 *
 * <p>{@code instruction} is the runtime-authored sentence that names what to change. The model does
 * not write it and does not pick the owner.
 */
public record OwnerDiagnosis(
    String narrative, String ownerStageId, boolean ambiguous, String instruction) {

  public OwnerDiagnosis {
    narrative = narrative == null ? "" : narrative;
    ownerStageId = ownerStageId == null ? "" : ownerStageId.trim();
    instruction = instruction == null ? "" : instruction.trim();
  }

  public static OwnerDiagnosis none(String narrative) {
    return new OwnerDiagnosis(narrative, "", false, "");
  }

  public static OwnerDiagnosis ask(String narrative) {
    return new OwnerDiagnosis(narrative, "", true, "");
  }

  public static OwnerDiagnosis of(String narrative, String ownerStageId) {
    return new OwnerDiagnosis(narrative, ownerStageId, false, "");
  }

  /** The same diagnosis carrying a runtime-authored instruction. */
  public OwnerDiagnosis withInstruction(String instruction) {
    return new OwnerDiagnosis(narrative, ownerStageId, ambiguous, instruction);
  }

  /** The same diagnosis pointed at another owner. The instruction rides along with it. */
  public OwnerDiagnosis withOwner(String ownerStageId) {
    return new OwnerDiagnosis(narrative, ownerStageId, false, instruction);
  }

  /** The same diagnosis turned into an owner question. The instruction rides along with it. */
  public OwnerDiagnosis asAsk() {
    return new OwnerDiagnosis(narrative, "", true, instruction);
  }

  public Optional<String> owner() {
    if (ambiguous || ownerStageId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(ownerStageId);
  }

  /**
   * Halt-card body: the narrative, then the instruction. {@code rawEvidence} stands in for a turn
   * that produced no narrative, which is the fallback the caller already takes when the turn fails.
   */
  public String cardBody(String rawEvidence) {
    String head = narrative.isBlank() ? (rawEvidence == null ? "" : rawEvidence) : narrative;
    if (instruction.isBlank()) {
      return head;
    }
    return head.isBlank() ? instruction : head + "\n\n" + instruction;
  }
}
