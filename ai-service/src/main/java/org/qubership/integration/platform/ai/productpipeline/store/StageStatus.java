package org.qubership.integration.platform.ai.productpipeline.store;

/** Durable stage lifecycle states for a product-pipeline run. */
public enum StageStatus {
  PENDING,
  RUNNING,
  WAITING_FOR_INPUT,
  WAITING_FOR_APPROVAL,
  SUCCEEDED,
  FAILED
}
