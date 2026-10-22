package org.qubership.integration.platform.ai.productpipeline.recovery;

/** Recovery action proposed by the failure narrative agent. */
public enum RecoveryAction {
  REVISE_BRIEF,
  REGENERATE_ARTIFACT,
  RETRY_OPERATION,
  ASK_USER,
  PARK
}
