package org.qubership.integration.platform.ai.productpipeline.runtime;

/** Raised when an approval targets a stale candidate, hash, or run revision. */
public final class StaleApprovalException extends RuntimeException {

  public StaleApprovalException(String message) {
    super(message);
  }
}
