package org.qubership.integration.platform.ai.productpipeline.capability;

/** Classifies the terminal outcome of one capability invocation. */
public enum StageOutcomeClass {
  SUCCEEDED,
  NEEDS_INPUT,
  CANDIDATE,
  RETRYABLE_TECHNICAL_FAILURE,
  VALIDATION_FAILURE,
  CONTRACT_FAILURE,
  POLICY_FAILURE,
  DOMAIN_FAILURE,
  MISSING_MANDATORY_INPUT
}
