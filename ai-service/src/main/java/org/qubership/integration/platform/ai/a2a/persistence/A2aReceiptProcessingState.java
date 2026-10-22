package org.qubership.integration.platform.ai.a2a.persistence;

/** Processing state for a caller-scoped Message receipt. */
public enum A2aReceiptProcessingState {
  CLAIMED,
  DISPATCHING,
  COMPLETED
}
