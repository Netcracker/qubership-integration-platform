package org.qubership.integration.platform.ai.productpipeline.store;

/** Durable run lifecycle states for a product-pipeline run. */
public enum RunStatus {
  RUNNING,
  WAITING_FOR_INPUT,
  WAITING_FOR_APPROVAL,
  WAITING_FOR_IMPLEMENT,
  FAILED,
  PLAN_APPROVED,
  CHAIN_MATERIALIZED
}
