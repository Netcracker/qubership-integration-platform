package org.qubership.integration.platform.ai.flow.persistence;

/**
 * Typed failure when durable Quarkus Flow persistence is unavailable. Startup must not continue
 * with in-memory workflow execution.
 */
public class FlowPersistenceException extends RuntimeException {

  public FlowPersistenceException(String message) {
    super(message);
  }

  public FlowPersistenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
