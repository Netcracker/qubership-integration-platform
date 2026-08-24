package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.Optional;

/**
 * Result of one owner-diagnosis turn: narrative text plus an owner from the closed candidate set,
 * or an ask when more than one candidate stays plausible.
 */
public record OwnerDiagnosis(String narrative, String ownerStageId, boolean ambiguous) {

  public OwnerDiagnosis {
    narrative = narrative == null ? "" : narrative;
    ownerStageId = ownerStageId == null ? "" : ownerStageId.trim();
  }

  public static OwnerDiagnosis none(String narrative) {
    return new OwnerDiagnosis(narrative, "", false);
  }

  public static OwnerDiagnosis ask(String narrative) {
    return new OwnerDiagnosis(narrative, "", true);
  }

  public static OwnerDiagnosis of(String narrative, String ownerStageId) {
    return new OwnerDiagnosis(narrative, ownerStageId, false);
  }

  public Optional<String> owner() {
    if (ambiguous || ownerStageId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(ownerStageId);
  }
}
