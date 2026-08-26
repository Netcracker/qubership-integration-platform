package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.Optional;

/**
 * Result of one owner-diagnosis turn: narrative text plus an owner from the closed candidate set,
 * or an ask when more than one candidate stays plausible.
 *
 * <p>The same turn also proposes a {@link HaltRemedy} and the sentence that states it. Both survive
 * validation only when the model stayed inside the closed sets; a dropped remedy leaves the
 * narrative untouched, and the runtime never writes a sentence of its own in its place.
 */
public record OwnerDiagnosis(
    String narrative,
    String ownerStageId,
    boolean ambiguous,
    HaltRemedy remedy,
    String instruction) {

  public OwnerDiagnosis {
    narrative = narrative == null ? "" : narrative;
    ownerStageId = ownerStageId == null ? "" : ownerStageId.trim();
    remedy = remedy == null ? HaltRemedy.NONE : remedy;
    instruction = remedy == HaltRemedy.NONE || instruction == null ? "" : instruction.trim();
  }

  public static OwnerDiagnosis none(String narrative) {
    return new OwnerDiagnosis(narrative, "", false, HaltRemedy.NONE, "");
  }

  public static OwnerDiagnosis ask(String narrative) {
    return new OwnerDiagnosis(narrative, "", true, HaltRemedy.NONE, "");
  }

  public static OwnerDiagnosis of(String narrative, String ownerStageId) {
    return new OwnerDiagnosis(narrative, ownerStageId, false, HaltRemedy.NONE, "");
  }

  /** The same diagnosis carrying a validated remedy. */
  public OwnerDiagnosis withRemedy(HaltRemedy remedy, String instruction) {
    return new OwnerDiagnosis(narrative, ownerStageId, ambiguous, remedy, instruction);
  }

  /** The same diagnosis pointed at another owner. The remedy rides along with it. */
  public OwnerDiagnosis withOwner(String ownerStageId) {
    return new OwnerDiagnosis(narrative, ownerStageId, false, remedy, instruction);
  }

  /** The same diagnosis turned into an owner question. The remedy rides along with it. */
  public OwnerDiagnosis asAsk() {
    return new OwnerDiagnosis(narrative, "", true, remedy, instruction);
  }

  public Optional<String> owner() {
    if (ambiguous || ownerStageId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(ownerStageId);
  }

  /**
   * Halt-card body: the narrative, then the remedy sentence when one survived validation.
   * {@code rawEvidence} stands in for a turn that produced no narrative, which is the fallback the
   * caller already takes when the turn fails.
   */
  public String cardBody(String rawEvidence) {
    String head = narrative.isBlank() ? (rawEvidence == null ? "" : rawEvidence) : narrative;
    if (instruction.isBlank()) {
      return head;
    }
    return head.isBlank() ? instruction : head + "\n\n" + instruction;
  }
}
