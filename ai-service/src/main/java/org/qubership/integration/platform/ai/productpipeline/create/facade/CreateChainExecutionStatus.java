package org.qubership.integration.platform.ai.productpipeline.create.facade;

/**
 * Transport-neutral execution status for create-chain snapshots and wait events.
 *
 * <p>Maps from pipeline run status without exposing internal enum names.
 */
public enum CreateChainExecutionStatus {
  WORKING,
  INPUT_REQUIRED,
  COMPLETED,
  FAILED
}
