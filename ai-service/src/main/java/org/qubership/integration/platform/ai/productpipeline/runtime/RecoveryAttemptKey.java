package org.qubership.integration.platform.ai.productpipeline.runtime;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;

/**
 * Identity of one recovery budget slot. {@code causeCode} and {@code evidenceIdentity} come from
 * the typed cause. {@code correctionEpoch} advances only when the owning producer's input artifact
 * changed. A batch retains each finding's key so removing another finding does not reset its budget.
 */
public record RecoveryAttemptKey(
    String ownerStageId,
    RecoveryCauseCode causeCode,
    String evidenceIdentity,
    int correctionEpoch,
    List<RecoveryAttemptKey> findingKeys) {

  public RecoveryAttemptKey {
    ownerStageId = ownerStageId == null ? "" : ownerStageId;
    causeCode = causeCode == null ? RecoveryCauseCode.VALIDATION_BLOCKER : causeCode;
    evidenceIdentity = evidenceIdentity == null ? "" : evidenceIdentity;
    correctionEpoch = Math.max(0, correctionEpoch);
    findingKeys = findingKeys == null ? List.of() : List.copyOf(findingKeys);
  }

  public RecoveryAttemptKey(
      String ownerStageId, RecoveryCauseCode causeCode, String evidenceIdentity, int correctionEpoch) {
    this(ownerStageId, causeCode, evidenceIdentity, correctionEpoch, List.of());
  }
}
