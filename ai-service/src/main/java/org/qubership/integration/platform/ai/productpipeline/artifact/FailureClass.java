package org.qubership.integration.platform.ai.productpipeline.artifact;

/** Classifies a durable product-pipeline failure for retry policy decisions. */
public enum FailureClass {
  TECHNICAL,
  VALIDATION,
  CONTRACT,
  POLICY,
  DOMAIN,
  MISSING_MANDATORY_INPUT,
  INTERRUPTED
}
