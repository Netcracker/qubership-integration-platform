package org.qubership.integration.platform.ai.productpipeline.runtime;

import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;

/**
 * Identity of one recovery budget slot. {@code causeCode} and {@code evidenceIdentity} come from
 * the typed cause. {@code correctionEpoch} advances only when the owning producer's input artifact
 * changed.
 */
public record RecoveryAttemptKey(
    String ownerStageId,
    RecoveryCauseCode causeCode,
    String evidenceIdentity,
    int correctionEpoch) {

  public RecoveryAttemptKey {
    ownerStageId = ownerStageId == null ? "" : ownerStageId;
    causeCode = causeCode == null ? RecoveryCauseCode.VALIDATION_BLOCKER : causeCode;
    evidenceIdentity = evidenceIdentity == null ? "" : evidenceIdentity;
    correctionEpoch = Math.max(0, correctionEpoch);
  }
}
