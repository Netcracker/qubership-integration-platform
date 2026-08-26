package org.qubership.integration.platform.ai.productpipeline.capability;

/** Classifies the terminal outcome of one capability invocation. */
public enum StageOutcomeClass {
  SUCCEEDED,
  NEEDS_INPUT,
  CANDIDATE,
  RETRYABLE_TECHNICAL_FAILURE,
  VALIDATION_FAILURE,

  /**
   * A model reply the stage contract rejects. An informed retry can clear it, so the halt card
   * keeps Retry.
   */
  CONTRACT_FAILURE,

  POLICY_FAILURE,
  DOMAIN_FAILURE,
  MISSING_MANDATORY_INPUT,

  /**
   * An invariant broken inside the service: a capability that emits the wrong number of completion
   * signals, an artifact kind the profile never declared, or a throwable nothing classified. No
   * author action clears it, so the halt card drops Retry and names the run instead.
   */
  INTERNAL_FAILURE
}
